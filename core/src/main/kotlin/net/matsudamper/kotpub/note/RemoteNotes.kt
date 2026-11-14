package net.matsudamper.kotpub.note

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.http.ResponseBody
import net.matsudamper.kotpub.json.AppJson

/**
 * 他のサーバーの投稿を id から取得する。
 *
 * ブーストは投稿の id だけを指してくるので、中身は投稿のあるサーバーから取りに行く。
 * `Announce` に投稿が埋まっていても使わない。ブーストした人の署名しか無く、
 * 投稿者が書いた中身だという裏付けにならない
 */
fun interface RemoteNotes {
    suspend fun find(noteId: String): RemoteNoteLookup
}

/**
 * [RemoteNotes.find] の結果。取れなかったときに、送り直せば取れる見込みがあるかを分ける。
 * 分けないと、相手のサーバーが一時的に落ちていただけでも、届いたブーストを捨てることになる
 */
sealed interface RemoteNoteLookup {
    data class Found(val note: ReceivedNote) : RemoteNoteLookup

    /** 相手が無いと答えた、投稿として読めない、条件を満たさない。取り直しても同じ結果になる */
    data class NotFound(val failure: RemoteFetchFailure) : RemoteNoteLookup

    /** つながらない、相手が 5xx や 429 を返したなど。時間をおけば取れる見込みがある */
    data class Unavailable(val failure: RemoteFetchFailure) : RemoteNoteLookup
}

/**
 * 取得先は相手が指定してきた URL なので、[net.matsudamper.kotpub.actor.HttpRemoteActors] と同じく次を守る。
 *
 * - https のみ
 * - リダイレクトで別ホストに移ったら捨てる。移った先のサーバーが他所の投稿を名乗れてしまう
 * - 文書の `id` が取得した URL と同じで、投稿者が同じホストであること
 * - 大きすぎる応答は読まない
 */
class HttpRemoteNotes(
    private val client: ActivityPubHttpClient,
) : RemoteNotes {

    override suspend fun find(noteId: String): RemoteNoteLookup = when (val lookup = findDocument(noteId)) {
        is RemoteNoteDocumentLookup.Found -> RemoteNoteLookup.Found(lookup.note)
        is RemoteNoteDocumentLookup.NotFound -> RemoteNoteLookup.NotFound(lookup.failure)
        is RemoteNoteDocumentLookup.Unavailable -> RemoteNoteLookup.Unavailable(lookup.failure)
    }

    /**
     * [find] と同じ確かめ方で取得し、読んだ投稿と一緒に取得した文書そのものも返す。
     * 投稿に付いたコレクション（`replies` など）は [ReceivedNote] に載らないので、文書から読む
     */
    internal suspend fun findDocument(noteId: String): RemoteNoteDocumentLookup {
        val noteUrl = HttpUrl.parseHttps(noteId)
        if (noteUrl == null) {
            return RemoteNoteDocumentLookup.NotFound(RemoteFetchFailure.NotHttps(noteId))
        }
        val json = when (val fetched = fetchSameHost(noteId, noteUrl)) {
            is RemoteDocumentFetch.Found -> fetched.document
            is RemoteDocumentFetch.NotFound -> return RemoteNoteDocumentLookup.NotFound(fetched.failure)
            is RemoteDocumentFetch.Unavailable -> return RemoteNoteDocumentLookup.Unavailable(fetched.failure)
        }
        val note = ReceivedNoteJson.read(json)
        if (note == null) {
            return RemoteNoteDocumentLookup.NotFound(RemoteFetchFailure.InvalidDocument(noteId, "投稿として読めない"))
        }
        if (note.id != noteId) {
            return RemoteNoteDocumentLookup.NotFound(RemoteFetchFailure.InvalidDocument(noteId, "id が取得先と違う: ${note.id}"))
        }
        val authorUrl = HttpUrl.parseHttps(note.attributedTo)
        if (authorUrl == null || !authorUrl.isSameHost(noteUrl)) {
            return RemoteNoteDocumentLookup.NotFound(RemoteFetchFailure.InvalidDocument(noteId, "投稿者が投稿と別のホスト: ${note.attributedTo}"))
        }
        return RemoteNoteDocumentLookup.Found(note, json)
    }

    /**
     * [originUrl] と同じホストの文書だけを取得する。リダイレクトで別ホストに移ったものも読まない
     */
    internal suspend fun fetchSameHost(url: String, originUrl: HttpUrl): RemoteDocumentFetch {
        val target = HttpUrl.parseHttps(url)
        if (target == null) {
            return RemoteDocumentFetch.NotFound(RemoteFetchFailure.NotHttps(url))
        }
        if (!target.isSameHost(originUrl)) {
            return RemoteDocumentFetch.NotFound(RemoteFetchFailure.DisallowedHost(url))
        }
        return fetch(url, originUrl)
    }

    private suspend fun fetch(url: String, noteUrl: HttpUrl): RemoteDocumentFetch {
        val response = try {
            client.get(
                url = url,
                headers = mapOf("Accept" to ActivityPubContentTypes.ActivityJson.toString()),
                maxBodyBytes = MAX_BODY_BYTES,
                readsBody = { status, finalUrl -> status == 200 && HttpUrl.parseHttps(finalUrl)?.isSameHost(noteUrl) == true },
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return RemoteDocumentFetch.Unavailable(RemoteFetchFailure.RequestFailed(url, exception))
        }
        if (response.status != 200) {
            val failure = RemoteFetchFailure.UnsuccessfulStatus(url, response.status)
            return if (isTemporaryFailure(response.status)) RemoteDocumentFetch.Unavailable(failure) else RemoteDocumentFetch.NotFound(failure)
        }
        if (HttpUrl.parseHttps(response.finalUrl)?.isSameHost(noteUrl) != true) {
            return RemoteDocumentFetch.NotFound(RemoteFetchFailure.RedirectedToDisallowedUrl(url, response.finalUrl))
        }
        val body = when (val responseBody = response.body) {
            is ResponseBody.Read -> responseBody.bytes

            is ResponseBody.Unreadable -> {
                return RemoteDocumentFetch.Unavailable(RemoteFetchFailure.BodyUnreadable(url, responseBody.failure))
            }

            ResponseBody.NotRead -> error("成功応答なのに本文を読んでいない: $url")
        }
        if (body.size > MAX_BODY_BYTES) {
            return RemoteDocumentFetch.NotFound(RemoteFetchFailure.BodyTooLarge(url, MAX_BODY_BYTES))
        }
        val document = runCatching { AppJson.parseToJsonElement(body.decodeToString()) }.getOrElse { failure ->
            return RemoteDocumentFetch.NotFound(RemoteFetchFailure.InvalidDocument(url, "JSON として読めない", failure))
        }
        if (document !is JsonObject) {
            return RemoteDocumentFetch.NotFound(RemoteFetchFailure.InvalidDocument(url, "JSON のオブジェクトではない"))
        }
        return RemoteDocumentFetch.Found(document)
    }

    private fun isTemporaryFailure(status: Int): Boolean =
        status >= 500 || status == HttpStatusCodes.TOO_MANY_REQUESTS || status == HttpStatusCodes.REQUEST_TIMEOUT

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}

/**
 * [HttpRemoteNotes.findDocument] の結果。分け方は [RemoteNoteLookup] と同じ
 */
internal sealed interface RemoteNoteDocumentLookup {
    data class Found(val note: ReceivedNote, val document: JsonObject) : RemoteNoteDocumentLookup

    data class NotFound(val failure: RemoteFetchFailure) : RemoteNoteDocumentLookup

    data class Unavailable(val failure: RemoteFetchFailure) : RemoteNoteDocumentLookup
}

/**
 * [HttpRemoteNotes.fetchSameHost] の結果。分け方は [RemoteNoteLookup] と同じ
 */
internal sealed interface RemoteDocumentFetch {
    data class Found(val document: JsonObject) : RemoteDocumentFetch

    data class NotFound(val failure: RemoteFetchFailure) : RemoteDocumentFetch

    data class Unavailable(val failure: RemoteFetchFailure) : RemoteDocumentFetch
}
