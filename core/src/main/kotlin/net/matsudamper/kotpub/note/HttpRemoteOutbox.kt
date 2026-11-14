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
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.http.ResponseBody
import org.slf4j.LoggerFactory

class HttpRemoteOutbox(
    private val client: ActivityPubHttpClient,
) {
    private val logger = LoggerFactory.getLogger(HttpRemoteOutbox::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 相手のアクター文書を取得し、outbox と featured を読む入口にする。
     * 同じ相手の outbox と featured を続けて読むときは、これを 1 回だけ呼んで各メソッドに渡すと、アクター文書を取り直さずに済む
     *
     * @return アクターが https でない、取得できない、取得したアクターの id が [actorId] と違うときは [RemoteActorCollectionsLookup.Unavailable]
     */
    suspend fun collectionsOf(actorId: String): RemoteActorCollectionsLookup {
        val actorUrl = HttpUrl.parseHttps(actorId)
        if (actorUrl == null) {
            return RemoteActorCollectionsLookup.Unavailable(RemoteFetchFailure.NotHttps(actorId))
        }
        val actor = when (val fetched = fetch(actorId, actorUrl)) {
            is DocumentFetch.Found -> fetched.document
            is DocumentFetch.Failed -> return RemoteActorCollectionsLookup.Unavailable(fetched.failure)
        }
        if (actor.text("id") != actorId) {
            return RemoteActorCollectionsLookup.Unavailable(RemoteFetchFailure.InvalidDocument(actorId, "アクターの id が取得先と違う: ${actor.text("id")}"))
        }
        return RemoteActorCollectionsLookup.Found(
            RemoteActorCollections(actorId = actorId, actorUrl = actorUrl, outbox = actor["outbox"], featured = actor["featured"]),
        )
    }

    /**
     * 相手の新しい投稿を [limit] 件まで。ページの途中を飛ばさずに先頭から読み進めるので、返す投稿の間に抜けは無い。
     * 読む量の上限に届いたら、そこまでに読めた分を返す
     */
    suspend fun recent(actorId: String, limit: Int): RemoteOutboxResult {
        val actor = when (val lookup = collectionsOf(actorId)) {
            is RemoteActorCollectionsLookup.Found -> lookup.collections
            is RemoteActorCollectionsLookup.Unavailable -> return RemoteOutboxResult.Unavailable(lookup.failure)
        }
        return when (val result = notesOf(actor = actor, collectionRef = actor.outbox, limit = limit)) {
            is RemoteOutboxResult.Found -> result.copy(notes = result.notes.newestFirst().take(limit))
            is RemoteOutboxResult.Unavailable -> result
        }
    }

    /**
     * 相手がプロフィールの先頭に固定した投稿。Actor の `featured` が指すコレクションを、相手が並べた順のまま返す。
     * `featured` が無ければ空
     */
    suspend fun featured(actorId: String, limit: Int): RemoteOutboxResult {
        val actor = when (val lookup = collectionsOf(actorId)) {
            is RemoteActorCollectionsLookup.Found -> lookup.collections
            is RemoteActorCollectionsLookup.Unavailable -> return RemoteOutboxResult.Unavailable(lookup.failure)
        }
        return featured(actor, limit)
    }

    /**
     * [featured] を、[collectionsOf] で取得済みのアクターから読む。アクター文書は取り直さない
     */
    suspend fun featured(actor: RemoteActorCollections, limit: Int): RemoteOutboxResult =
        notesOf(actor = actor, collectionRef = actor.featured, limit = limit)

    /**
     * outbox を新しい方から少しずつ読む。続きは [RemoteOutboxPage.next] を [from] に渡して読む。
     *
     * 1 回に読む項目は [MAX_ITEMS_PER_CALL] 件、取りに行くページは [MAX_PAGES] 枚までにする。項目が投稿の参照なら 1 件ずつ取りに行くので、
     * 相手のページが大きくてもリクエストの数を抑えるため。上限で止めたときは、読み終えた次の項目を [RemoteOutboxPage.next] で指すので、項目を飛ばさない。
     * id の無いページは後から指せないので、項目の数によらず今読み切る。読める投稿が無ければ、上限まで続けて先も読む
     *
     * @param from 読み始める位置。null なら最初のページの先頭
     */
    suspend fun recentPage(actorId: String, from: RemoteOutboxCursor?): RemoteOutboxPageResult {
        if (from == null) {
            return when (val lookup = collectionsOf(actorId)) {
                is RemoteActorCollectionsLookup.Found -> recentPage(lookup.collections, null)
                is RemoteActorCollectionsLookup.Unavailable -> RemoteOutboxPageResult.Unavailable(lookup.failure)
            }
        }
        val actorUrl = HttpUrl.parseHttps(actorId)
        if (actorUrl == null) {
            return RemoteOutboxPageResult.Unavailable(RemoteFetchFailure.NotHttps(actorId))
        }
        return continuedPage(actorId = actorId, actorUrl = actorUrl, from = from)
    }

    /**
     * [recentPage] を、[collectionsOf] で取得済みのアクターから読む。アクター文書は取り直さない
     */
    suspend fun recentPage(actor: RemoteActorCollections, from: RemoteOutboxCursor?): RemoteOutboxPageResult {
        if (from != null) return continuedPage(actorId = actor.actorId, actorUrl = actor.actorUrl, from = from)
        val page = when (val first = firstPage(actor = actor, collectionRef = actor.outbox)) {
            is FirstPage.Found -> first.page
            FirstPage.Empty -> return RemoteOutboxPageResult.Found(RemoteOutboxPage(notes = listOf(), next = null))
            is FirstPage.Unavailable -> return RemoteOutboxPageResult.Unavailable(first.failure)
        }
        return readPage(actorId = actor.actorId, actorUrl = actor.actorUrl, page = page, itemOffset = 0)
    }

    private suspend fun continuedPage(actorId: String, actorUrl: HttpUrl, from: RemoteOutboxCursor): RemoteOutboxPageResult {
        val page = when (val fetched = fetch(from.pageId, actorUrl)) {
            is DocumentFetch.Found -> fetched.document

            is DocumentFetch.Failed -> {
                return RemoteOutboxPageResult.Unavailable(fetched.failure)
            }
        }
        return readPage(actorId = actorId, actorUrl = actorUrl, page = page, itemOffset = from.itemOffset)
    }

    private suspend fun readPage(actorId: String, actorUrl: HttpUrl, page: JsonObject, itemOffset: Int): RemoteOutboxPageResult {
        val reader = ContiguousReader(actorId = actorId, actorUrl = actorUrl, noteLimit = Int.MAX_VALUE, wantsMore = { it.isEmpty() })
        return when (val progress = reader.read(page, itemOffset)) {
            is ReadProgress.Stopped -> RemoteOutboxPageResult.Found(RemoteOutboxPage(notes = progress.notes.newestFirst(), next = progress.next))
            is ReadProgress.Failed -> RemoteOutboxPageResult.Unavailable(progress.failure)
        }
    }

    /**
     * @param collectionRef Actor のうち、投稿を並べたコレクションを指す項目の値
     */
    private suspend fun notesOf(actor: RemoteActorCollections, collectionRef: JsonElement?, limit: Int): RemoteOutboxResult {
        require(limit in 1..MAX_NOTES)
        val actorId = actor.actorId
        val firstPage = when (val first = firstPage(actor = actor, collectionRef = collectionRef)) {
            is FirstPage.Found -> first.page
            FirstPage.Empty -> return RemoteOutboxResult.Found(listOf())
            is FirstPage.Unavailable -> return RemoteOutboxResult.Unavailable(first.failure)
        }
        val reader = ContiguousReader(actorId = actorId, actorUrl = actor.actorUrl, noteLimit = limit, wantsMore = { it.size < limit })
        return when (val progress = reader.read(firstPage, 0)) {
            is ReadProgress.Stopped -> {
                if (progress.notes.size < limit && progress.next != null) {
                    RemoteOutboxResult.Found(progress.notes, RemoteOutboxIncomplete.ReadLimitReached(progress.next))
                } else {
                    RemoteOutboxResult.Found(progress.notes)
                }
            }

            // 先頭から抜け無く読めた分なので、途中で読めなくなってもそこまでは返す
            is ReadProgress.Failed -> RemoteOutboxResult.Found(progress.notes, RemoteOutboxIncomplete.PageUnavailable(progress.failure))
        }
    }

    private suspend fun firstPage(actor: RemoteActorCollections, collectionRef: JsonElement?): FirstPage {
        // 項目が無いのは相手が並べていないだけなので、空として返す
        if (collectionRef == null) return FirstPage.Empty
        val collection = when (val fetched = reference(collectionRef, actor.actorId, actor.actorUrl)) {
            is DocumentFetch.Found -> fetched.document

            is DocumentFetch.Failed -> {
                return FirstPage.Unavailable(fetched.failure)
            }
        }
        if (collection.items() != null) return FirstPage.Found(collection)
        // first も無ければ空のコレクション。指しているのに取れなければ失敗
        val firstRef = collection["first"] ?: return FirstPage.Empty
        val first = when (val fetched = reference(firstRef, actor.actorId, actor.actorUrl)) {
            is DocumentFetch.Found -> fetched.document

            is DocumentFetch.Failed -> {
                return FirstPage.Unavailable(fetched.failure)
            }
        }
        return FirstPage.Found(first)
    }

    /**
     * ページの項目を、ページの途中も含めて飛ばさずに順に読み進める。どこで止めても、止めた位置の続きを指せるようにする
     *
     * @param noteLimit ここまで集まったら、ページの途中でも止める
     * @param wantsMore 集まった投稿を見て、続きを読むか決める
     */
    private inner class ContiguousReader(
        private val actorId: String,
        private val actorUrl: HttpUrl,
        private val noteLimit: Int,
        private val wantsMore: (List<RemoteOutboxNote>) -> Boolean,
    ) {
        suspend fun read(page: JsonObject, itemOffset: Int): ReadProgress =
            read(page, itemOffset, MAX_ITEMS_PER_CALL, MAX_PAGES - 1, setOf(), listOf())

        /**
         * @param remainingItems まだ読んでよい項目の数
         * @param remainingPageFetches まだ取りに行ってよいページの数
         */
        private suspend fun read(
            page: JsonObject,
            itemOffset: Int,
            remainingItems: Int,
            remainingPageFetches: Int,
            visitedPageIds: Set<String>,
            collectedNotes: List<RemoteOutboxNote>,
        ): ReadProgress {
            val pageId = page.text("id")
            val items = page.items().orEmpty()
            val unreadItems = items.drop(itemOffset)
            // id の無いページは後から指せないので、上限によらず読み切る
            val readableItems = if (pageId == null) unreadItems else unreadItems.take(remainingItems)
            val collected = collectUniqueNotes(readableItems, 0, actorId, actorUrl, noteLimit, collectedNotes)
            val notes = collected.notes
            val nextItemOffset = itemOffset + collected.consumedItems
            val itemsLeft = (remainingItems - collected.consumedItems).coerceAtLeast(0)
            if (pageId != null && nextItemOffset < items.size) {
                val restOfPage = RemoteOutboxCursor(pageId = pageId, itemOffset = nextItemOffset)
                if (!wantsMore(notes) || notes.size >= noteLimit || itemsLeft == 0) return ReadProgress.Stopped(notes, restOfPage)
                return read(page, nextItemOffset, itemsLeft, remainingPageFetches, visitedPageIds, notes)
            }
            val visited = visitedPageIds + listOfNotNull(pageId)
            val nextPageRef = page["next"]
            if (nextPageRef is JsonObject && nextPageRef.text("id") == null) {
                // 続きとして指せないので、集め終えていなければ読む投稿があっても辿る
                if (notes.size >= noteLimit) return ReadProgress.Stopped(notes, null)
                return read(nextPageRef, 0, itemsLeft, remainingPageFetches, visited, notes)
            }
            val nextPageId = when (nextPageRef) {
                is JsonPrimitive -> nextPageRef.takeIf { it.isString }?.content
                is JsonObject -> nextPageRef.text("id")
                else -> null
            }?.takeIf { it !in visited }
            if (nextPageId == null) return ReadProgress.Stopped(notes, null)
            val nextPage = RemoteOutboxCursor(pageId = nextPageId, itemOffset = 0)
            if (!wantsMore(notes) || itemsLeft == 0 || remainingPageFetches <= 0) return ReadProgress.Stopped(notes, nextPage)
            val nextPageDocument = when (val fetched = reference(nextPageRef, actorId, actorUrl)) {
                is DocumentFetch.Found -> fetched.document

                is DocumentFetch.Failed -> {
                    return ReadProgress.Failed(notes, fetched.failure)
                }
            }
            return read(nextPageDocument, 0, itemsLeft, remainingPageFetches - 1, visited, notes)
        }
    }

    private sealed interface ReadProgress {
        /**
         * @param next 続きの位置。最後まで読んだら null
         */
        data class Stopped(val notes: List<RemoteOutboxNote>, val next: RemoteOutboxCursor?) : ReadProgress

        /**
         * @param notes 読めなくなるまでに、先頭から抜け無く読めた分
         */
        data class Failed(val notes: List<RemoteOutboxNote>, val failure: RemoteFetchFailure) : ReadProgress
    }

    private sealed interface FirstPage {
        data class Found(val page: JsonObject) : FirstPage

        /** 相手が並べていないか、空のコレクション */
        data object Empty : FirstPage

        data class Unavailable(val failure: RemoteFetchFailure) : FirstPage
    }

    private sealed interface DocumentFetch {
        data class Found(val document: JsonObject) : DocumentFetch

        data class Failed(val failure: RemoteFetchFailure) : DocumentFetch
    }

    private fun List<RemoteOutboxNote>.newestFirst(): List<RemoteOutboxNote> = sortedByDescending { Instant.parse(it.publishedAt) }

    /**
     * @param consumedItems 読んだ項目の数。[limit] で止めたときは、そこまでの数
     */
    private data class CollectedNotes(val notes: List<RemoteOutboxNote>, val consumedItems: Int)

    private tailrec suspend fun collectUniqueNotes(
        items: List<JsonElement>,
        index: Int,
        actorId: String,
        actorUrl: HttpUrl,
        limit: Int,
        collectedNotes: List<RemoteOutboxNote>,
    ): CollectedNotes {
        if (collectedNotes.size >= limit) return CollectedNotes(collectedNotes, index)
        val item = items.getOrNull(index) ?: return CollectedNotes(collectedNotes, index)
        val note = readItem(item, actorId, actorUrl)
        val notes = if (note == null || collectedNotes.any { it.id == note.id }) collectedNotes else collectedNotes + note
        return collectUniqueNotes(items, index + 1, actorId, actorUrl, limit, notes)
    }

    private suspend fun readItem(item: JsonElement, actorId: String, actorUrl: HttpUrl): RemoteOutboxNote? {
        val activity = foundDocument(item, actorUrl) ?: return null
        val note = when (activity.text("type")) {
            "Create" -> {
                if (activity.text("actor") != actorId) return null
                foundDocument(activity["object"], actorUrl)
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

    /**
     * 項目が読めなければ、その項目だけを飛ばす。飛ばした項目は結果に載らないので、取得できなかった理由はログに残す
     */
    private suspend fun foundDocument(value: JsonElement?, actorUrl: HttpUrl): JsonObject? = when (value) {
        is JsonObject -> value

        is JsonPrimitive -> when (val fetched = fetch(value.content, actorUrl)) {
            is DocumentFetch.Found -> fetched.document

            is DocumentFetch.Failed -> {
                logger.warn("投稿を取得できないので飛ばす: ${fetched.failure}")
                null
            }
        }

        else -> null
    }

    /**
     * コレクションやページを指す値を読む。埋め込まれていればそのまま使い、URL なら取得する
     *
     * @param referrer 値が書かれていた文書。値が URL でも埋め込みでもないときの理由に載せる
     */
    private suspend fun reference(value: JsonElement?, referrer: String, actorUrl: HttpUrl): DocumentFetch = when (value) {
        is JsonObject -> DocumentFetch.Found(value)
        is JsonPrimitive -> fetch(value.content, actorUrl)
        else -> DocumentFetch.Failed(RemoteFetchFailure.InvalidDocument(referrer, "コレクションやページを指す値が URL でもオブジェクトでもない"))
    }

    private suspend fun fetch(url: String, actorUrl: HttpUrl): DocumentFetch {
        val target = HttpUrl.parseHttps(url)
        if (target == null) {
            return DocumentFetch.Failed(RemoteFetchFailure.NotHttps(url))
        }
        if (!target.isSameHost(actorUrl)) {
            return DocumentFetch.Failed(RemoteFetchFailure.DisallowedHost(url))
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
            return DocumentFetch.Failed(RemoteFetchFailure.RequestFailed(url, exception))
        }
        if (response.status != 200) {
            return DocumentFetch.Failed(RemoteFetchFailure.UnsuccessfulStatus(url, response.status))
        }
        if (HttpUrl.parseHttps(response.finalUrl)?.isSameHost(actorUrl) != true) {
            return DocumentFetch.Failed(RemoteFetchFailure.RedirectedToDisallowedUrl(url, response.finalUrl))
        }
        val body = when (val responseBody = response.body) {
            is ResponseBody.Read -> responseBody.bytes

            is ResponseBody.Unreadable -> {
                return DocumentFetch.Failed(RemoteFetchFailure.BodyUnreadable(url, responseBody.failure))
            }

            ResponseBody.NotRead -> error("成功応答なのに本文を読んでいない: $url")
        }
        if (body.size > MAX_BODY_BYTES) {
            return DocumentFetch.Failed(RemoteFetchFailure.BodyTooLarge(url, MAX_BODY_BYTES))
        }
        val document = runCatching { json.parseToJsonElement(body.decodeToString()) }.getOrElse { failure ->
            return DocumentFetch.Failed(RemoteFetchFailure.InvalidDocument(url, "JSON として読めない", failure))
        }
        if (document !is JsonObject) {
            return DocumentFetch.Failed(RemoteFetchFailure.InvalidDocument(url, "JSON のオブジェクトではない"))
        }
        return DocumentFetch.Found(document)
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.items(): JsonArray? = (this["orderedItems"] ?: this["items"]) as? JsonArray

    private companion object {
        const val MAX_NOTES = 20
        const val MAX_PAGES = 3
        const val MAX_ITEMS_PER_CALL = 300
        const val MAX_BODY_BYTES = 1024 * 1024
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
    }
}

/**
 * [HttpRemoteOutbox.collectionsOf] で取得した相手のアクターのうち、投稿を並べたコレクションを指す部分。
 * 中身は [HttpRemoteOutbox] だけが読む
 *
 * @param actorId 取得したアクターの id。取得先と同じ
 */
class RemoteActorCollections internal constructor(
    val actorId: String,
    internal val actorUrl: HttpUrl,
    internal val outbox: JsonElement?,
    internal val featured: JsonElement?,
)

/**
 * [HttpRemoteOutbox.collectionsOf] の結果
 */
sealed interface RemoteActorCollectionsLookup {
    data class Found(val collections: RemoteActorCollections) : RemoteActorCollectionsLookup

    /** アクターを取得できない、または取得したアクターの id が取得先と違う */
    data class Unavailable(val failure: RemoteFetchFailure) : RemoteActorCollectionsLookup
}

/**
 * [HttpRemoteOutbox] の結果。取得に失敗したことを「投稿が無い」と同じ空のリストで返すと、
 * 呼び出し側は相手が投稿していないのか、こちらが読めなかったのかを区別できない
 */
sealed interface RemoteOutboxResult {
    /**
     * @param notes 相手が投稿を並べていなければ空。先頭から抜け無く読めた分で、途中のページを取れなかったときや、読む量の上限に達したときは、それまでの分
     * @param incomplete 最後まで読まずに止めたときの理由。読み切ったか、求めた件数が集まったなら null
     */
    data class Found(
        val notes: List<RemoteOutboxNote>,
        val incomplete: RemoteOutboxIncomplete? = null,
    ) : RemoteOutboxResult

    /** アクターやコレクションを取得できない、または中身が条件を満たさない */
    data class Unavailable(val failure: RemoteFetchFailure) : RemoteOutboxResult
}

/**
 * [RemoteOutboxResult.Found] が、求めた件数に届く前に読むのを止めた理由
 */
sealed interface RemoteOutboxIncomplete {
    /**
     * 1 回に読む量の上限に達した
     *
     * @param next 読み終えた次の位置
     */
    data class ReadLimitReached(val next: RemoteOutboxCursor) : RemoteOutboxIncomplete

    /** 続きのページを取得できなかった */
    data class PageUnavailable(val failure: RemoteFetchFailure) : RemoteOutboxIncomplete
}

/**
 * [HttpRemoteOutbox.recentPage] の結果。[RemoteOutboxResult] と同じく、読めなかったことを投稿が無いことと分ける
 */
sealed interface RemoteOutboxPageResult {
    data class Found(val page: RemoteOutboxPage) : RemoteOutboxPageResult

    /** アクター・コレクション・ページを取得できない、または中身が条件を満たさない */
    data class Unavailable(val failure: RemoteFetchFailure) : RemoteOutboxPageResult
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
