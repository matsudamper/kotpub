package net.matsudamper.kotpub.note

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.http.RemoteFetchFailure

/**
 * 他のサーバーの投稿の、リプライ・いいね・ブーストの数を読む。
 *
 * 投稿は [HttpRemoteNotes] と同じ確かめ方で取得する。投稿の `replies`・`likes`・`shares` が指すコレクションやページは、
 * 投稿と同じホストのものだけを読む。別のホストのコレクションは、投稿のサーバーが数を名乗っている裏付けにならない。
 *
 * 数の出し方はサーバーごとに違う。
 * - Mastodon は `replies` に `totalItems` を付けず、`first`（埋め込みのページのことが多い）から `next` を辿る形で出す。
 *   4.3 以降は `likes`・`shares` を `totalItems` 付きで出す
 * - Misskey は `replies` を出さない
 * - Pleroma 系などは `replies` に `totalItems` を付けることがある
 */
class HttpRemoteNoteCounts(
    client: ActivityPubHttpClient,
) {
    private val notes = HttpRemoteNotes(client)

    /**
     * @param maxReplyPages リプライを数えるために読むページの上限。`totalItems` が無く、ページの項目を数えるときだけ使う
     */
    suspend fun count(noteId: String, maxReplyPages: Int): RemoteNoteCountsResult {
        require(maxReplyPages >= 1)
        val fetched = when (val lookup = notes.findDocument(noteId)) {
            is RemoteNoteDocumentLookup.Found -> lookup
            is RemoteNoteDocumentLookup.NotFound -> return RemoteNoteCountsResult.NotFound(lookup.failure)
            is RemoteNoteDocumentLookup.Unavailable -> return RemoteNoteCountsResult.Unavailable(lookup.failure)
        }
        val noteUrl = HttpUrl.parseHttps(noteId) ?: error("取得できた投稿の id が https でない: $noteId")
        val document = fetched.document
        return RemoteNoteCountsResult.Found(
            replies = countReplies(document["replies"], noteUrl, maxReplyPages),
            likes = totalItemsOf(document["likes"], noteUrl),
            shares = totalItemsOf(document["shares"], noteUrl),
        )
    }

    private suspend fun countReplies(repliesRef: JsonElement?, noteUrl: HttpUrl, maxPages: Int): RemoteReplyCount? {
        if (repliesRef == null) return null
        val collection = resolve(repliesRef, noteUrl) ?: return null
        val totalItems = collection.totalItems()
        if (totalItems != null) return RemoteReplyCount(count = totalItems, complete = true)
        if (collection.items() != null) return countPages(collection, noteUrl, remainingPages = maxPages - 1, visitedPageIds = setOf(), counted = 0)
        // first も無ければリプライの無いコレクション
        val firstRef = collection["first"] ?: return RemoteReplyCount(count = 0, complete = true)
        val firstPage = resolve(firstRef, noteUrl) ?: return null
        return countPages(firstPage, noteUrl, remainingPages = maxPages - 1, visitedPageIds = setOfNotNull(collection.text("id")), counted = 0)
    }

    /**
     * @param remainingPages [page] の後に、まだ読んでよいページの数
     */
    private tailrec suspend fun countPages(
        page: JsonObject,
        noteUrl: HttpUrl,
        remainingPages: Int,
        visitedPageIds: Set<String>,
        counted: Long,
    ): RemoteReplyCount {
        val total = counted + page.items().orEmpty().size
        val visited = visitedPageIds + setOfNotNull(page.text("id"))
        val nextRef = page["next"]
        val nextPageId = when (nextRef) {
            is JsonPrimitive -> nextRef.takeIf { it.isString }?.content
            is JsonObject -> nextRef.text("id")
            else -> null
        }
        val hasNext = nextRef is JsonObject || nextPageId != null
        if (!hasNext || (nextPageId != null && nextPageId in visited)) return RemoteReplyCount(count = total, complete = true)
        if (remainingPages <= 0) return RemoteReplyCount(count = total, complete = false)
        // 続きを読めなかったら、そこまでの数を数え切れていないものとして返す
        val nextPage = resolve(nextRef, noteUrl) ?: return RemoteReplyCount(count = total, complete = false)
        return countPages(nextPage, noteUrl, remainingPages - 1, visited, total)
    }

    /**
     * いいねとブーストは `totalItems` があるときだけ数える。項目を辿って数えるとリクエストが増えるうえ、
     * `totalItems` を付けないサーバーは中身も並べないことが多い
     */
    private suspend fun totalItemsOf(collectionRef: JsonElement?, noteUrl: HttpUrl): Long? {
        if (collectionRef == null) return null
        return resolve(collectionRef, noteUrl)?.totalItems()
    }

    /**
     * 埋め込まれていればそのまま使い、URL なら投稿と同じホストのときだけ取得する。読めなければ null
     */
    private suspend fun resolve(value: JsonElement?, noteUrl: HttpUrl): JsonObject? = when (value) {
        is JsonObject -> value

        is JsonPrimitive -> if (value.isString) {
            when (val fetched = notes.fetchSameHost(value.content, noteUrl)) {
                is RemoteDocumentFetch.Found -> fetched.document
                is RemoteDocumentFetch.NotFound -> null
                is RemoteDocumentFetch.Unavailable -> null
            }
        } else {
            null
        }

        else -> null
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.items(): JsonArray? = (this["orderedItems"] ?: this["items"]) as? JsonArray

    private fun JsonObject.totalItems(): Long? = (this["totalItems"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
}

/**
 * [HttpRemoteNoteCounts.count] の結果。分け方は [RemoteNoteLookup] と同じ
 */
sealed interface RemoteNoteCountsResult {
    /**
     * 各値は、投稿がそのコレクションを出していない、数を読めない、または取得できなかったときは null
     *
     * @param likes いいねの数
     * @param shares ブーストの数
     */
    data class Found(
        val replies: RemoteReplyCount?,
        val likes: Long?,
        val shares: Long?,
    ) : RemoteNoteCountsResult

    /** 相手が無いと答えた、投稿として読めない、条件を満たさない。取り直しても同じ結果になる */
    data class NotFound(val failure: RemoteFetchFailure) : RemoteNoteCountsResult

    /** つながらない、相手が 5xx や 429 を返したなど。時間をおけば取れる見込みがある */
    data class Unavailable(val failure: RemoteFetchFailure) : RemoteNoteCountsResult
}

/**
 * @param complete 最後まで数えたか。ページの上限に達した、または続きのページを読めなかったときは false で、[count] はそこまでの数
 */
data class RemoteReplyCount(
    val count: Long,
    val complete: Boolean,
)
