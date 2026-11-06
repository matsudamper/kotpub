package net.matsudamper.kotpub.note

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.json.AppJson

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
    override suspend fun find(noteId: String): ReceivedNote? {
        val noteUrl = HttpUrl.parseHttps(noteId) ?: return null
        val json = fetch(noteId, noteUrl) ?: return null
        val note = ReceivedNoteJson.read(json) ?: return null
        if (note.id != noteId) return null
        val authorUrl = HttpUrl.parseHttps(note.attributedTo) ?: return null
        if (!authorUrl.isSameHost(noteUrl)) return null
        return note
    }

    private suspend fun fetch(url: String, noteUrl: HttpUrl): JsonObject? {
        return try {
            val response = client.get(
                url = url,
                headers = mapOf("Accept" to ActivityPubContentTypes.ActivityJson.toString()),
                maxBodyBytes = MAX_BODY_BYTES,
                readsBody = { status, finalUrl -> status == 200 && HttpUrl.parseHttps(finalUrl)?.isSameHost(noteUrl) == true },
            )
            if (response.status != 200 || HttpUrl.parseHttps(response.finalUrl)?.isSameHost(noteUrl) != true) return null
            val body = response.body?.getOrNull() ?: return null
            if (body.size > MAX_BODY_BYTES) return null
            AppJson.parseToJsonElement(body.decodeToString()) as? JsonObject
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
