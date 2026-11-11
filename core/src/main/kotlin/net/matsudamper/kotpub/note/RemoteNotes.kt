package net.matsudamper.kotpub.note

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.http.ActivityPubHttpClient
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
    /**
     * @return 取得できなかったか、投稿として読めなければ null
     */
    suspend fun find(noteId: String): ReceivedNote?
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

    override suspend fun find(noteId: String): ReceivedNote? {
        val noteUrl = HttpUrl.parseHttps(noteId) ?: return null
        val json = fetch(noteId, noteUrl) ?: return null
        val note = ReceivedNoteJson.read(json)
        if (note == null) {
            logger.warn("投稿として読めなかった: $noteId")
            return null
        }
        if (note.id != noteId) {
            logger.warn("取得した投稿の id が取得先と違う: $noteId id=${note.id}")
            return null
        }
        val authorUrl = HttpUrl.parseHttps(note.attributedTo)
        if (authorUrl == null || !authorUrl.isSameHost(noteUrl)) {
            logger.warn("投稿者が投稿と別のホストなので捨てる: $noteId 投稿者=${note.attributedTo}")
            return null
        }
        return note
    }

    private suspend fun fetch(url: String, noteUrl: HttpUrl): JsonObject? {
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
            return null
        }
        if (response.status != 200) {
            logger.warn("投稿を取得できなかった: $url status=${response.status}")
            return null
        }
        if (HttpUrl.parseHttps(response.finalUrl)?.isSameHost(noteUrl) != true) {
            logger.warn("投稿の取得で別のホストに移ったので捨てる: $url → ${response.finalUrl}")
            return null
        }
        val body = checkNotNull(response.body) { "成功応答なのに本文を読んでいない: $url" }.getOrElse { failure ->
            logger.warn("投稿の本文を読めなかった: $url", failure)
            return null
        }
        if (body.size > MAX_BODY_BYTES) {
            logger.warn("投稿の本文が大きすぎるので捨てる: $url")
            return null
        }
        val document = runCatching { AppJson.parseToJsonElement(body.decodeToString()) }.getOrElse { failure ->
            logger.warn("投稿を JSON として読めなかった: $url", failure)
            return null
        }
        if (document !is JsonObject) {
            logger.warn("投稿が JSON のオブジェクトではないので捨てる: $url")
            return null
        }
        return document
    }

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
