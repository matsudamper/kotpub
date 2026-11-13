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
     * 項目が投稿の参照なら 1 件ずつ取りに行くので、1 回に取りに行く数を [MAX_ITEM_FETCHES] 回までにする。
     * 埋め込まれた項目は取りに行かないので数えない。上限に達してページの途中で止めたときは、
     * 同じページの続きを [RemoteOutboxPage.next] で指す。id の無いページは後から指せないので、
     * 上限に達したら残りを捨てて理由をログに残す。読める投稿が無ければ、続けて先も読む
     *
     * @param from 読み始める位置。null なら最初のページの先頭
     */
    suspend fun recentPage(actorId: String, from: RemoteOutboxCursor?): RemoteOutboxPageResult {
        val actorUrl = HttpUrl.parseHttps(actorId)
        if (actorUrl == null) {
            logger.warn("https のアクターではないので outbox を読まない: $actorId")
            return RemoteOutboxPageResult.Unavailable
        }
        val page = if (from == null) {
            when (val first = firstPage(actorId = actorId, actorUrl = actorUrl, collectionName = "outbox")) {
                is FirstPage.Found -> first.page
                FirstPage.Empty -> return RemoteOutboxPageResult.Found(RemoteOutboxPage(notes = listOf(), next = null))
                FirstPage.Unavailable -> return RemoteOutboxPageResult.Unavailable
            }
        } else {
            fetch(from.pageId, actorUrl) ?: run {
                logger.warn("outbox のページを読めなかった: actor=$actorId page=${from.pageId}")
                return RemoteOutboxPageResult.Unavailable
            }
        }
        return readFrom(page, from?.itemOffset ?: 0, actorId, actorUrl, MAX_PAGES, ItemFetchBudget(MAX_ITEM_FETCHES), setOf(), listOf())
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
        val firstPage = when (val first = firstPage(actorId = actorId, actorUrl = actorUrl, collectionName = collectionName)) {
            is FirstPage.Found -> first.page
            FirstPage.Empty -> return RemoteOutboxResult.Found(listOf())
            FirstPage.Unavailable -> return RemoteOutboxResult.Unavailable
        }
        return RemoteOutboxResult.Found(pages(firstPage, actorId, actorUrl, limit, MAX_PAGES, ItemFetchBudget(MAX_ITEM_FETCHES), setOf(), listOf()))
    }

    private suspend fun firstPage(actorId: String, actorUrl: HttpUrl, collectionName: String): FirstPage {
        val actor = fetch(actorId, actorUrl) ?: return FirstPage.Unavailable
        if (actor.text("id") != actorId) {
            logger.warn("取得したアクターの id が取得先と違う: $actorId id=${actor.text("id")}")
            return FirstPage.Unavailable
        }
        // 項目が無いのは相手が並べていないだけなので、空として返す
        val collectionRef = actor[collectionName] ?: return FirstPage.Empty
        val collection = document(collectionRef, actorUrl)
        if (collection == null) {
            logger.warn("$collectionName を読めなかった: actor=$actorId")
            return FirstPage.Unavailable
        }
        if (collection.items() != null) return FirstPage.Found(collection)
        // first も無ければ空のコレクション。指しているのに取れなければ失敗
        val firstRef = collection["first"] ?: return FirstPage.Empty
        val first = document(firstRef, actorUrl)
        if (first == null) {
            logger.warn("$collectionName の最初のページを読めなかった: actor=$actorId")
            return FirstPage.Unavailable
        }
        return FirstPage.Found(first)
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
    ): RemoteOutboxPageResult {
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
            return found(notes = notes, next = RemoteOutboxCursor(pageId = pageId, itemOffset = nextItemOffset))
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
        if (nextPageId == null) return found(notes = notes, next = null)
        if (notes.isNotEmpty() || remainingSteps <= 1) return found(notes = notes, next = RemoteOutboxCursor(pageId = nextPageId, itemOffset = 0))
        val nextPage = document(nextPageRef, actorUrl)
        if (nextPage == null) {
            logger.warn("outbox の続きのページを読めなかった: actor=$actorId page=$nextPageId")
            return RemoteOutboxPageResult.Unavailable
        }
        return readFrom(nextPage, 0, actorId, actorUrl, remainingSteps - 1, budget, visited, notes)
    }

    private fun found(notes: List<RemoteOutboxNote>, next: RemoteOutboxCursor?): RemoteOutboxPageResult =
        RemoteOutboxPageResult.Found(RemoteOutboxPage(notes = notes.sortedByDescending { Instant.parse(it.publishedAt) }, next = next))

    private sealed interface FirstPage {
        data class Found(val page: JsonObject) : FirstPage

        /** 相手が並べていないか、空のコレクション */
        data object Empty : FirstPage

        data object Unavailable : FirstPage
    }

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
        if (remainingPages <= 1) {
            if (page["next"] != null) {
                logger.warn("読むページ数の上限に達したので続きのページを読まない: actor=$actorId page=$pageId 集まった投稿=${notes.size}")
            }
            return notes
        }
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
        if (!budget.covers(item, actorId)) return CollectedNotes(collectedNotes, index)
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
        fun covers(item: JsonElement, actorId: String): Boolean {
            val fetchesBeforeReading = when {
                item is JsonPrimitive -> 1
                item is JsonObject && isCreateReferringNote(item, actorId) -> 1
                else -> 0
            }
            // 上限を超えて負になっていても、取りに行かない項目は読む
            return fetchesBeforeReading == 0 || remaining >= fetchesBeforeReading
        }

        /** readItem が object を取りに行くのは、その actor の Create だけ */
        private fun isCreateReferringNote(activity: JsonObject, actorId: String): Boolean =
            (activity["type"] as? JsonPrimitive)?.content == "Create" &&
                (activity["actor"] as? JsonPrimitive)?.content == actorId &&
                activity["object"] is JsonPrimitive

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
 * [HttpRemoteOutbox.recentPage] の結果。[RemoteOutboxResult] と同じく、読めなかったことを投稿が無いことと分ける
 */
sealed interface RemoteOutboxPageResult {
    data class Found(val page: RemoteOutboxPage) : RemoteOutboxPageResult

    /** アクター・コレクション・ページを取得できない、または中身が条件を満たさない。理由はログに残す */
    data object Unavailable : RemoteOutboxPageResult
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
