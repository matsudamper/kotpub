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
import net.matsudamper.kotpub.http.ResponseBody
import org.slf4j.LoggerFactory

class HttpRemoteOutbox(
    private val client: ActivityPubHttpClient,
) {
    private val logger = LoggerFactory.getLogger(HttpRemoteOutbox::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun recent(actorId: String, limit: Int): RemoteOutboxResult =
        when (val result = notesOf(actorId = actorId, collectionName = "outbox", limit = limit)) {
            is RemoteOutboxResult.Found -> RemoteOutboxResult.Found(
                result.notes.sortedByDescending { Instant.parse(it.publishedAt) }.take(limit),
            )

            RemoteOutboxResult.Unavailable -> result
        }

    /**
     * 相手がプロフィールの先頭に固定した投稿。Actor の `featured` が指すコレクションを、相手が並べた順のまま返す。
     * `featured` が無ければ空
     */
    suspend fun featured(actorId: String, limit: Int): RemoteOutboxResult =
        notesOf(actorId = actorId, collectionName = "featured", limit = limit)

    /**
     * outbox を新しい方から少しずつ読む。続きは [RemoteOutboxPage.next] を [from] に渡して読む。
     *
     * 1 回に読む項目はページごとに [MAX_ITEMS_PER_PAGE] 件までにする。項目が投稿の参照なら 1 件ずつ取りに行くので、
     * 相手のページが大きくてもリクエストの数を抑えるため。ページの途中で止めたときは、同じページの続きを [RemoteOutboxPage.next] で指す。
     * id の無いページは後から指せないので、項目の数によらず今読み切る。読める投稿が無ければ、続けて先も読む
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
        return readFrom(page, from?.itemOffset ?: 0, actorId, actorUrl, MAX_PAGES, setOf(), listOf())
    }

    /**
     * @param collectionName Actor のうち、投稿を並べたコレクションを指す項目の名前
     */
    private suspend fun notesOf(actorId: String, collectionName: String, limit: Int): RemoteOutboxResult {
        require(limit in 1..MAX_NOTES)
        val actorUrl = HttpUrl.parseHttps(actorId)
        if (actorUrl == null) {
            logger.warn("https のアクターではないので $collectionName を読まない: $actorId")
            return RemoteOutboxResult.Unavailable
        }
        val actor = fetch(actorId, actorUrl) ?: return RemoteOutboxResult.Unavailable
        if (actor.text("id") != actorId) {
            logger.warn("取得したアクターの id が取得先と違う: $actorId id=${actor.text("id")}")
            return RemoteOutboxResult.Unavailable
        }
        // 項目が無いのは相手が並べていないだけなので、空として返す
        val collectionRef = actor[collectionName] ?: return RemoteOutboxResult.Found(listOf())
        val collection = document(collectionRef, actorUrl)
        if (collection == null) {
            logger.warn("$collectionName を読めなかった: actor=$actorId")
            return RemoteOutboxResult.Unavailable
        }
        val firstPage = if (collection.items() == null) {
            // first も無ければ空のコレクション。指しているのに取れなければ失敗
            val firstRef = collection["first"] ?: return RemoteOutboxResult.Found(listOf())
            val first = document(firstRef, actorUrl)
            if (first == null) {
                logger.warn("$collectionName の最初のページを読めなかった: actor=$actorId")
                return RemoteOutboxResult.Unavailable
            }
            first
        } else {
            collection
        }
        return RemoteOutboxResult.Found(pages(firstPage, actorId, actorUrl, limit, MAX_PAGES, setOf(), listOf()))
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
        visitedPageIds: Set<String>,
        collectedNotes: List<RemoteOutboxNote>,
    ): RemoteOutboxPage {
        val pageId = page.text("id")
        val items = page.items().orEmpty()
        val remainingItems = items.drop(itemOffset)
        val chunk = if (pageId == null) remainingItems else remainingItems.take(MAX_ITEMS_PER_PAGE)
        val notes = collectUniqueNotes(chunk, 0, actorId, actorUrl, Int.MAX_VALUE, collectedNotes)
        val nextItemOffset = itemOffset + chunk.size
        if (pageId != null && nextItemOffset < items.size) {
            if (notes.isNotEmpty() || remainingSteps <= 1) {
                return RemoteOutboxPage(notes = notes.newestFirst(), next = RemoteOutboxCursor(pageId = pageId, itemOffset = nextItemOffset))
            }
            return readFrom(page, nextItemOffset, actorId, actorUrl, remainingSteps - 1, visitedPageIds, notes)
        }
        val visited = visitedPageIds + listOfNotNull(pageId)
        val nextPageRef = page["next"]
        if (nextPageRef is JsonObject && nextPageRef.text("id") == null) {
            return readFrom(nextPageRef, 0, actorId, actorUrl, remainingSteps, visited, notes)
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
        return readFrom(nextPage, 0, actorId, actorUrl, remainingSteps - 1, visited, notes)
    }

    private fun List<RemoteOutboxNote>.newestFirst(): List<RemoteOutboxNote> = sortedByDescending { Instant.parse(it.publishedAt) }

    private suspend fun pages(
        page: JsonObject,
        actorId: String,
        actorUrl: HttpUrl,
        limit: Int,
        remainingPages: Int,
        visitedPageIds: Set<String>,
        collectedNotes: List<RemoteOutboxNote>,
    ): List<RemoteOutboxNote> {
        val pageId = page.text("id")
        if (pageId != null && pageId in visitedPageIds) return collectedNotes
        val allPageItems = page.items().orEmpty()
        if (allPageItems.size > MAX_ITEMS_PER_PAGE) {
            logger.warn(
                "1ページの項目が上限を超えたので ${allPageItems.size - MAX_ITEMS_PER_PAGE} 件を読まずに捨てる: " +
                    "actor=$actorId page=$pageId 項目数=${allPageItems.size}",
            )
        }
        val pageItems = allPageItems.take(MAX_ITEMS_PER_PAGE)
        val notes = collectUniqueNotes(pageItems, 0, actorId, actorUrl, limit, collectedNotes)
        if (notes.size >= limit) return notes
        if (remainingPages <= 1) {
            if (page["next"] != null) {
                logger.warn("読むページ数の上限に達したので続きのページを読まない: actor=$actorId page=$pageId 集まった投稿=${notes.size}")
            }
            return notes
        }
        val nextPage = document(page["next"], actorUrl) ?: return notes
        return pages(nextPage, actorId, actorUrl, limit, remainingPages - 1, visitedPageIds + listOfNotNull(pageId), notes)
    }

    private tailrec suspend fun collectUniqueNotes(
        items: List<JsonElement>,
        index: Int,
        actorId: String,
        actorUrl: HttpUrl,
        limit: Int,
        collectedNotes: List<RemoteOutboxNote>,
    ): List<RemoteOutboxNote> {
        if (collectedNotes.size >= limit) return collectedNotes
        val item = items.getOrNull(index) ?: return collectedNotes
        val note = readItem(item, actorId, actorUrl)
        val notes = if (note == null || collectedNotes.any { it.id == note.id }) collectedNotes else collectedNotes + note
        return collectUniqueNotes(items, index + 1, actorId, actorUrl, limit, notes)
    }

    private suspend fun readItem(item: JsonElement, actorId: String, actorUrl: HttpUrl): RemoteOutboxNote? {
        val activity = document(item, actorUrl) ?: return null
        val note = when (activity.text("type")) {
            "Create" -> {
                if (activity.text("actor") != actorId) return null
                document(activity["object"], actorUrl)
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
        val publishedParseFailure = runCatching { Instant.parse(publishedAt) }.exceptionOrNull()
        if (publishedParseFailure != null) {
            logger.warn("投稿の published を読めないので捨てる: $id", publishedParseFailure)
            return null
        }
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

    private suspend fun document(value: JsonElement?, actorUrl: HttpUrl): JsonObject? = when (value) {
        is JsonObject -> value
        is JsonPrimitive -> fetch(value.content, actorUrl)
        else -> null
    }

    private suspend fun fetch(url: String, actorUrl: HttpUrl): JsonObject? {
        val target = HttpUrl.parseHttps(url)
        if (target == null) {
            logger.warn("https の URL ではないので取得しない: $url")
            return null
        }
        if (!target.isSameHost(actorUrl)) {
            logger.warn("アクターと別のホストなので取得しない: $url")
            return null
        }
        val response = try {
            client.get(
                url = url,
                headers = mapOf("Accept" to "application/activity+json"),
                maxBodyBytes = MAX_BODY_BYTES,
                readsBody = { status, finalUrl -> status == 200 && HttpUrl.parseHttps(finalUrl)?.isSameHost(actorUrl) == true },
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("取得に失敗した: $url", exception)
            return null
        }
        if (response.status != 200) {
            logger.warn("取得できなかった: $url status=${response.status}")
            return null
        }
        if (HttpUrl.parseHttps(response.finalUrl)?.isSameHost(actorUrl) != true) {
            logger.warn("別のホストに移ったので捨てる: $url → ${response.finalUrl}")
            return null
        }
        val body = when (val responseBody = response.body) {
            is ResponseBody.Read -> responseBody.bytes

            is ResponseBody.Unreadable -> {
                logger.warn("本文を読めなかった: $url", responseBody.failure)
                return null
            }

            ResponseBody.NotRead -> error("成功応答なのに本文を読んでいない: $url")
        }
        if (body.size > MAX_BODY_BYTES) {
            logger.warn("本文が大きすぎるので捨てる: $url")
            return null
        }
        val document = runCatching { json.parseToJsonElement(body.decodeToString()) }.getOrElse { failure ->
            logger.warn("JSON として読めなかった: $url", failure)
            return null
        }
        if (document !is JsonObject) {
            logger.warn("JSON のオブジェクトではないので捨てる: $url")
            return null
        }
        return document
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.items(): JsonArray? = (this["orderedItems"] ?: this["items"]) as? JsonArray

    private companion object {
        const val MAX_NOTES = 20
        const val MAX_PAGES = 3
        const val MAX_ITEMS_PER_PAGE = 100
        const val MAX_BODY_BYTES = 1024 * 1024
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
    }
}

/**
 * [HttpRemoteOutbox] の結果。取得に失敗したことを「投稿が無い」と同じ空のリストで返すと、
 * 呼び出し側は相手が投稿していないのか、こちらが読めなかったのかを区別できない
 */
sealed interface RemoteOutboxResult {
    /**
     * @param notes 相手が投稿を並べていなければ空。2 ページ目以降を取れなかったときは、それまでに読めた分
     */
    data class Found(val notes: List<RemoteOutboxNote>) : RemoteOutboxResult

    /** アクターやコレクションを取得できない、または中身が条件を満たさない。理由はログに残す */
    data object Unavailable : RemoteOutboxResult
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
