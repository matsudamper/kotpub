package net.matsudamper.kotpub.note

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.emoji.CustomEmoji
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.HttpUrl
import org.slf4j.LoggerFactory

class HttpRemoteOutbox(
    private val client: ActivityPubHttpClient,
) {
    private val logger = LoggerFactory.getLogger(HttpRemoteOutbox::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun recent(actorId: String, limit: Int): List<RemoteOutboxNote> =
        notesOf(actorId = actorId, collectionName = "outbox", limit = limit)
            .sortedByDescending { Instant.parse(it.publishedAt) }
            .take(limit)

    /**
     * 相手がプロフィールの先頭に固定した投稿。Actor の `featured` が指すコレクションを、相手が並べた順のまま返す。
     * `featured` が無ければ空
     */
    suspend fun featured(actorId: String, limit: Int): List<RemoteOutboxNote> =
        notesOf(actorId = actorId, collectionName = "featured", limit = limit)

    /**
     * outbox を新しい方から少しずつ読む。続きは [RemoteOutboxPage.next] を [from] に渡して読む。
     *
     * 項目が投稿の参照なら 1 件ずつ取りに行くので、1 回に取りに行く数を [MAX_ITEM_FETCHES] 回までにする。
     * 埋め込まれた項目は取りに行かないので数えない。上限に達してページの途中で止めたときは、
     * 同じページの続きを [RemoteOutboxPage.next] で指す。id の無いページは後から指せないので、
     * 上限に達したら残りを捨てて理由をログに残す。読める投稿が無ければ、続けて先も読む
     *
     * @param from 読み始める位置。null なら最初のページの先頭
     */
    suspend fun recentPage(actorId: String, from: RemoteOutboxCursor?): RemoteOutboxPage {
        val actorUrl = HttpUrl.parseHttps(actorId) ?: return RemoteOutboxPage(notes = listOf(), next = null)
        val page = if (from == null) {
            firstPage(actorId = actorId, actorUrl = actorUrl, collectionName = "outbox")
        } else {
            fetch(from.pageId, actorUrl) ?: error("outbox のページを取得できませんでした")
        }
        if (page == null) return RemoteOutboxPage(notes = listOf(), next = null)
        return readFrom(page, from?.itemOffset ?: 0, actorId, actorUrl, MAX_PAGES, ItemFetchBudget(MAX_ITEM_FETCHES), setOf(), listOf())
    }

    /**
     * @param collectionName Actor のうち、投稿を並べたコレクションを指す項目の名前
     */
    private suspend fun notesOf(actorId: String, collectionName: String, limit: Int): List<RemoteOutboxNote> {
        require(limit in 1..MAX_NOTES)
        val actorUrl = HttpUrl.parseHttps(actorId) ?: return listOf()
        val firstPage = firstPage(actorId = actorId, actorUrl = actorUrl, collectionName = collectionName) ?: return listOf()
        return pages(firstPage, actorId, actorUrl, limit, MAX_PAGES, ItemFetchBudget(MAX_ITEM_FETCHES), setOf(), listOf())
    }

    /**
     * @return コレクションが無いか、最初のページが無ければ null
     */
    private suspend fun firstPage(actorId: String, actorUrl: HttpUrl, collectionName: String): JsonObject? {
        val actor = fetch(actorId, actorUrl) ?: error("アクターを取得できませんでした")
        check(actor.text("id") == actorId) { "アクターの ID が一致しません" }
        val collectionRef = actor[collectionName] ?: return null
        val collection = document(collectionRef, actorUrl) ?: error("$collectionName を取得できませんでした")
        return if (collection.items() == null) document(collection["first"], actorUrl) else collection
    }

    /**
     * @param remainingSteps 読める投稿が無いときに、続けて読んでよい回数
     */
    private suspend fun readFrom(
        page: JsonObject,
        itemOffset: Int,
        actorId: String,
        actorUrl: HttpUrl,
        remainingSteps: Int,
        budget: ItemFetchBudget,
        visitedPageIds: Set<String>,
        collectedNotes: List<RemoteOutboxNote>,
    ): RemoteOutboxPage {
        val pageId = page.text("id")
        val items = page.items().orEmpty()
        val collected = collectUniqueNotes(items, itemOffset, actorId, actorUrl, Int.MAX_VALUE, budget, collectedNotes)
        val notes = collected.notes
        val nextItemOffset = collected.nextIndex
        if (pageId == null && nextItemOffset < items.size) {
            logger.warn(
                "取りに行く回数の上限に達したが、id の無いページは続きを指せないので ${items.size - nextItemOffset} 件を読まずに捨てる: actor=$actorId",
            )
        }
        if (pageId != null && nextItemOffset < items.size) {
            return RemoteOutboxPage(notes = notes.newestFirst(), next = RemoteOutboxCursor(pageId = pageId, itemOffset = nextItemOffset))
        }
        val visited = visitedPageIds + listOfNotNull(pageId)
        val nextPageRef = page["next"]
        if (nextPageRef is JsonObject && nextPageRef.text("id") == null) {
            return readFrom(nextPageRef, 0, actorId, actorUrl, remainingSteps, budget, visited, notes)
        }
        val nextPageId = when (nextPageRef) {
            is JsonPrimitive -> nextPageRef.takeIf { it.isString }?.content
            is JsonObject -> nextPageRef.text("id")
            else -> null
        }?.takeIf { it !in visited }
        if (nextPageId == null) return RemoteOutboxPage(notes = notes.newestFirst(), next = null)
        if (notes.isNotEmpty() || remainingSteps <= 1) {
            return RemoteOutboxPage(notes = notes.newestFirst(), next = RemoteOutboxCursor(pageId = nextPageId, itemOffset = 0))
        }
        val nextPage = document(nextPageRef, actorUrl) ?: error("outbox のページを取得できませんでした")
        return readFrom(nextPage, 0, actorId, actorUrl, remainingSteps - 1, budget, visited, notes)
    }

    private fun List<RemoteOutboxNote>.newestFirst(): List<RemoteOutboxNote> = sortedByDescending { Instant.parse(it.publishedAt) }

    private suspend fun pages(
        page: JsonObject,
        actorId: String,
        actorUrl: HttpUrl,
        limit: Int,
        remainingPages: Int,
        budget: ItemFetchBudget,
        visitedPageIds: Set<String>,
        collectedNotes: List<RemoteOutboxNote>,
    ): List<RemoteOutboxNote> {
        val pageId = page.text("id")
        if (pageId != null && pageId in visitedPageIds) return collectedNotes
        val pageItems = page.items().orEmpty()
        val collected = collectUniqueNotes(pageItems, 0, actorId, actorUrl, limit, budget, collectedNotes)
        val notes = collected.notes
        if (notes.size >= limit) return notes
        if (collected.nextIndex < pageItems.size) {
            logger.warn(
                "取りに行く回数の上限に達したので ${pageItems.size - collected.nextIndex} 件を読まずに終える: actor=$actorId page=$pageId 集まった投稿=${notes.size}",
            )
            return notes
        }
        if (remainingPages <= 1) return notes
        val nextPage = document(page["next"], actorUrl) ?: return notes
        return pages(nextPage, actorId, actorUrl, limit, remainingPages - 1, budget, visitedPageIds + listOfNotNull(pageId), notes)
    }

    /**
     * 件数が [limit] に達するか、次の項目を取りに行く余裕が [budget] に無くなるまで読む
     */
    private tailrec suspend fun collectUniqueNotes(
        items: List<JsonElement>,
        index: Int,
        actorId: String,
        actorUrl: HttpUrl,
        limit: Int,
        budget: ItemFetchBudget,
        collectedNotes: List<RemoteOutboxNote>,
    ): CollectedNotes {
        if (collectedNotes.size >= limit) return CollectedNotes(collectedNotes, index)
        val item = items.getOrNull(index) ?: return CollectedNotes(collectedNotes, index)
        if (!budget.covers(item)) return CollectedNotes(collectedNotes, index)
        val note = readItem(item, actorId, actorUrl, budget)
        val notes = if (note == null || collectedNotes.any { it.id == note.id }) collectedNotes else collectedNotes + note
        return collectUniqueNotes(items, index + 1, actorId, actorUrl, limit, budget, notes)
    }

    private suspend fun readItem(item: JsonElement, actorId: String, actorUrl: HttpUrl, budget: ItemFetchBudget): RemoteOutboxNote? {
        val activity = itemDocument(item, actorUrl, budget) ?: return null
        val note = when (activity.text("type")) {
            "Create" -> {
                if (activity.text("actor") != actorId) return null
                itemDocument(activity["object"], actorUrl, budget)
            }

            "Note" -> activity

            else -> null
        } ?: return null
        return readNote(note, actorId)
    }

    private fun readNote(note: JsonObject, actorId: String): RemoteOutboxNote? {
        if (note.text("type") != "Note" || note.text("attributedTo") != actorId) return null
        val to = note["to"].audience()
        val cc = note["cc"].audience()
        if (PUBLIC !in to && PUBLIC !in cc) return null
        val id = note.text("id") ?: return null
        val content = note.text("content") ?: return null
        val publishedAt = note.text("published") ?: return null
        if (runCatching { Instant.parse(publishedAt) }.isFailure) return null
        val received = ReceivedNoteJson.read(note) ?: return null
        return RemoteOutboxNote(
            id = id,
            contentHtml = content,
            publishedAt = publishedAt,
            summary = received.summary,
            sensitive = received.sensitive,
            attachments = received.attachments,
            emojis = received.emojis,
            unlisted = PUBLIC !in to,
        )
    }

    private fun JsonElement?.audience(): List<String> =
        (if (this is JsonArray) this else listOfNotNull(this)).mapNotNull { (it as? JsonPrimitive)?.content }

    private suspend fun itemDocument(value: JsonElement?, actorUrl: HttpUrl, budget: ItemFetchBudget): JsonObject? {
        if (value is JsonPrimitive) budget.spend()
        return document(value, actorUrl)
    }

    private suspend fun document(value: JsonElement?, actorUrl: HttpUrl): JsonObject? = when (value) {
        is JsonObject -> value
        is JsonPrimitive -> fetch(value.content, actorUrl)
        else -> null
    }

    private suspend fun fetch(url: String, actorUrl: HttpUrl): JsonObject? {
        val target = HttpUrl.parseHttps(url) ?: return null
        if (!target.isSameHost(actorUrl)) return null
        return try {
            val response = client.get(
                url = url,
                headers = mapOf("Accept" to "application/activity+json"),
                maxBodyBytes = MAX_BODY_BYTES,
                readsBody = { status, finalUrl -> status == 200 && HttpUrl.parseHttps(finalUrl)?.isSameHost(actorUrl) == true },
            )
            if (response.status != 200 || HttpUrl.parseHttps(response.finalUrl)?.isSameHost(actorUrl) != true) return null
            val body = response.body?.getOrNull() ?: return null
            if (body.size > MAX_BODY_BYTES) return null
            json.parseToJsonElement(body.decodeToString()) as? JsonObject
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.items(): JsonArray? = (this["orderedItems"] ?: this["items"]) as? JsonArray

    private class CollectedNotes(
        val notes: List<RemoteOutboxNote>,
        val nextIndex: Int,
    )

    /**
     * 1 回の読み込みで、項目を取りに行ってよい残りの回数。ページとコレクションの取得は [MAX_PAGES] で抑えるので数えない
     */
    private class ItemFetchBudget(private var remaining: Int) {
        /**
         * 参照の項目は、取りに行った Create が投稿をまた参照で持っていることがある。
         * それは取りに行くまで分からないので、上限を 1 回超えることがある
         */
        fun covers(item: JsonElement): Boolean {
            val fetchesBeforeReading = when {
                item is JsonPrimitive -> 1
                item is JsonObject && item["object"] is JsonPrimitive -> 1
                else -> 0
            }
            return remaining >= fetchesBeforeReading
        }

        fun spend() {
            remaining -= 1
        }
    }

    private companion object {
        const val MAX_NOTES = 20
        const val MAX_PAGES = 3
        const val MAX_ITEM_FETCHES = 100
        const val MAX_BODY_BYTES = 1024 * 1024
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
    }
}

/**
 * @param notes 新しい順
 * @param next 続きを読むときに [HttpRemoteOutbox.recentPage] に渡す位置。続きが無ければ null
 */
data class RemoteOutboxPage(
    val notes: List<RemoteOutboxNote>,
    val next: RemoteOutboxCursor?,
)

/**
 * outbox のどこまで読んだか
 *
 * @param pageId 次に読むページの id
 * @param itemOffset そのページの何件目から読むか
 */
data class RemoteOutboxCursor(
    val pageId: String,
    val itemOffset: Int,
)

/**
 * @param summary 閲覧注意の注意書き。無ければ null
 * @param attachments 添付。付いた順
 * @param emojis 本文と注意書きに `:name:` で入るカスタム絵文字
 * @param unlisted 公開タイムラインに載せない公開の投稿か。Public が `to` に無く `cc` にだけある
 */
data class RemoteOutboxNote(
    val id: String,
    val contentHtml: String,
    val publishedAt: String,
    val summary: String?,
    val sensitive: Boolean,
    val attachments: List<ReceivedNoteAttachment>,
    val emojis: List<CustomEmoji>,
    val unlisted: Boolean,
)
