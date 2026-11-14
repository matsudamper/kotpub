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

    override suspend fun find(noteId: String): RemoteNoteLookup {
        val noteUrl = HttpUrl.parseHttps(noteId)
        if (noteUrl == null) {
            return RemoteNoteLookup.NotFound(RemoteFetchFailure.NotHttps(noteId))
        }
        val json = when (val fetched = fetch(noteId, noteUrl)) {
            is DocumentFetch.Found -> fetched.document
            is DocumentFetch.NotFound -> return RemoteNoteLookup.NotFound(fetched.failure)
            is DocumentFetch.Unavailable -> return RemoteNoteLookup.Unavailable(fetched.failure)
        }
        val note = ReceivedNoteJson.read(json)
        if (note == null) {
            return RemoteNoteLookup.NotFound(RemoteFetchFailure.InvalidDocument(noteId, "投稿として読めない"))
        }
        if (note.id != noteId) {
            return RemoteNoteLookup.NotFound(RemoteFetchFailure.InvalidDocument(noteId, "id が取得先と違う: ${note.id}"))
        }
        val authorUrl = HttpUrl.parseHttps(note.attributedTo)
        if (authorUrl == null || !authorUrl.isSameHost(noteUrl)) {
            return RemoteNoteLookup.NotFound(RemoteFetchFailure.InvalidDocument(noteId, "投稿者が投稿と別のホスト: ${note.attributedTo}"))
        }
        return RemoteNoteLookup.Found(note)
    }

    private suspend fun fetch(url: String, noteUrl: HttpUrl): DocumentFetch {
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
            return DocumentFetch.Unavailable(RemoteFetchFailure.RequestFailed(url, exception))
        }
        if (response.status != 200) {
            val failure = RemoteFetchFailure.UnsuccessfulStatus(url, response.status)
            return if (isTemporaryFailure(response.status)) DocumentFetch.Unavailable(failure) else DocumentFetch.NotFound(failure)
        }
        if (HttpUrl.parseHttps(response.finalUrl)?.isSameHost(noteUrl) != true) {
            return DocumentFetch.NotFound(RemoteFetchFailure.RedirectedToDisallowedUrl(url, response.finalUrl))
        }
        val body = when (val responseBody = response.body) {
            is ResponseBody.Read -> responseBody.bytes

            is ResponseBody.Unreadable -> {
                return DocumentFetch.Unavailable(RemoteFetchFailure.BodyUnreadable(url, responseBody.failure))
            }

            ResponseBody.NotRead -> error("成功応答なのに本文を読んでいない: $url")
        }
        if (body.size > MAX_BODY_BYTES) {
            return DocumentFetch.NotFound(RemoteFetchFailure.BodyTooLarge(url, MAX_BODY_BYTES))
        }
        val document = runCatching { AppJson.parseToJsonElement(body.decodeToString()) }.getOrElse { failure ->
            return DocumentFetch.NotFound(RemoteFetchFailure.InvalidDocument(url, "JSON として読めない", failure))
        }
        if (document !is JsonObject) {
            return DocumentFetch.NotFound(RemoteFetchFailure.InvalidDocument(url, "JSON のオブジェクトではない"))
        }
        return DocumentFetch.Found(document)
    }

    private fun isTemporaryFailure(status: Int): Boolean =
        status >= 500 || status == HttpStatusCodes.TOO_MANY_REQUESTS || status == HttpStatusCodes.REQUEST_TIMEOUT

    private sealed interface DocumentFetch {
        data class Found(val document: JsonObject) : DocumentFetch

        data class NotFound(val failure: RemoteFetchFailure) : DocumentFetch

        data class Unavailable(val failure: RemoteFetchFailure) : DocumentFetch
    }

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
