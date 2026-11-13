package net.matsudamper.kotpub.note

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.json.AppJson
import org.slf4j.LoggerFactory

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
    data object NotFound : RemoteNoteLookup

    /** つながらない、相手が 5xx や 429 を返したなど。時間をおけば取れる見込みがある */
    data object Unavailable : RemoteNoteLookup
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
    private val logger = LoggerFactory.getLogger(HttpRemoteNotes::class.java)

    override suspend fun find(noteId: String): RemoteNoteLookup {
        val noteUrl = HttpUrl.parseHttps(noteId)
        if (noteUrl == null) {
            logger.warn("https の URL ではないので投稿を取得しない: $noteId")
            return RemoteNoteLookup.NotFound
        }
        val json = when (val fetched = fetch(noteId, noteUrl)) {
            is DocumentFetch.Found -> fetched.document
            DocumentFetch.NotFound -> return RemoteNoteLookup.NotFound
            DocumentFetch.Unavailable -> return RemoteNoteLookup.Unavailable
        }
        val note = ReceivedNoteJson.read(json)
        if (note == null) {
            logger.warn("投稿として読めなかった: $noteId")
            return RemoteNoteLookup.NotFound
        }
        if (note.id != noteId) {
            logger.warn("取得した投稿の id が取得先と違う: $noteId id=${note.id}")
            return RemoteNoteLookup.NotFound
        }
        val authorUrl = HttpUrl.parseHttps(note.attributedTo)
        if (authorUrl == null || !authorUrl.isSameHost(noteUrl)) {
            logger.warn("投稿者が投稿と別のホストなので捨てる: $noteId 投稿者=${note.attributedTo}")
            return RemoteNoteLookup.NotFound
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
            logger.warn("投稿の取得に失敗した: $url", exception)
            return DocumentFetch.Unavailable
        }
        if (response.status != 200) {
            logger.warn("投稿を取得できなかった: $url status=${response.status}")
            return if (isTemporaryFailure(response.status)) DocumentFetch.Unavailable else DocumentFetch.NotFound
        }
        if (HttpUrl.parseHttps(response.finalUrl)?.isSameHost(noteUrl) != true) {
            logger.warn("投稿の取得で別のホストに移ったので捨てる: $url → ${response.finalUrl}")
            return DocumentFetch.NotFound
        }
        val body = checkNotNull(response.body) { "成功応答なのに本文を読んでいない: $url" }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            logger.warn("投稿の本文を読めなかった: $url", failure)
            return DocumentFetch.Unavailable
        }
        if (body.size > MAX_BODY_BYTES) {
            logger.warn("投稿の本文が大きすぎるので捨てる: $url")
            return DocumentFetch.NotFound
        }
        val document = runCatching { AppJson.parseToJsonElement(body.decodeToString()) }.getOrElse { failure ->
            logger.warn("投稿を JSON として読めなかった: $url", failure)
            return DocumentFetch.NotFound
        }
        if (document !is JsonObject) {
            logger.warn("投稿が JSON のオブジェクトではないので捨てる: $url")
            return DocumentFetch.NotFound
        }
        return DocumentFetch.Found(document)
    }

    private fun isTemporaryFailure(status: Int): Boolean =
        status >= 500 || status == HttpStatusCodes.TOO_MANY_REQUESTS || status == HttpStatusCodes.REQUEST_TIMEOUT

    private sealed interface DocumentFetch {
        data class Found(val document: JsonObject) : DocumentFetch

        data object NotFound : DocumentFetch

        data object Unavailable : DocumentFetch
    }

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
