package net.matsudamper.activitypub.actor

import kotlin.time.Duration
import net.matsudamper.activitypub.http.EndpointResponse
import net.matsudamper.activitypub.http.HttpStatusCodes

/**
 * アクターのプロフィールヘッダー。Actor JSON の `image` が指す先。
 *
 * 中身は使う側が [ActorHeaders] で渡す。相手にこの URL を渡しておくと、
 * 画像の置き場を変えてもヘッダーの URL は変わらない。
 */
class ActorHeaderEndpoint(
    private val directory: ActorDirectory,
    private val headers: ActorHeaders,
) {
    /**
     * `/users/{username}/header`
     *
     * @param version クエリの `v`。[ActorUrls.header] が付ける値
     */
    suspend fun get(
        username: String?,
        version: String?,
    ): EndpointResponse {
        // 後からアカウントやヘッダーが増えれば同じ URL で出るようになる。
        // 見に来た側が 404 を持っていると、出るようになった後も出ない
        val urls = directory.resolve(username)
            ?: return notFound("アクターが見つからない: $username")

        val header = headers.find(urls.username)
            ?: return notFound("ヘッダーが無い: ${urls.username}")

        return EndpointResponse(
            status = HttpStatusCodes.OK,
            contentType = header.contentType,
            body = header.bytes,
            headers = mapOf(
                CACHE_CONTROL_HEADER to header.cacheControl(version),
                CONTENT_TYPE_OPTIONS_HEADER to CONTENT_TYPE_OPTIONS,
            ),
        )
    }

    private fun notFound(text: String): EndpointResponse =
        EndpointResponse.text(
            status = HttpStatusCodes.NOT_FOUND,
            text = text,
            headers = mapOf(CACHE_CONTROL_HEADER to NO_STORE),
        )
}

/**
 * アクターのプロフィールヘッダーの引き先。
 *
 * どこから取ってくるかは Actor 側の関心ではないので、
 * [StoredActorNames] と同じく名前を渡して引けることだけを決めておく。
 */
interface ActorHeaders {
    /**
     * 名前で引く。ヘッダーが無ければ null。
     *
     * 渡すのは [StoredActorNames] が返した保存側の綴り。
     */
    suspend fun find(username: String): ActorHeader?
}

/**
 * プロフィールヘッダーの中身。
 *
 * @param contentType 取得元が名乗った種類。そのまま返す
 * @param version 中身から決まる値。要求された値と一致すれば同じ中身と分かる
 * @param cacheFor 見に来た側に持たせる時間。0 なら持たせない
 */
class ActorHeader(
    val bytes: ByteArray,
    val contentType: String,
    val version: String,
    val cacheFor: Duration,
)

/**
 * 見に来た側に持たせる時間。
 *
 * [ActorUrls.header] が渡す URL には中身から決まる値が付いている。その値が今置いてあるものと
 * 一致していれば、同じ URL の中身は入れ替わらない（入れ替わるときは URL ごと変わる）ので長く持たせる。
 *
 * ただし [cacheFor] が尽きているときは値が一致していても持たせない。中身は次の取り込みで
 * 入れ替わるので、そこで長く持たせると入れ替わる直前の画像が 1 年出続ける
 */
private fun ActorHeader.cacheControl(requestedVersion: String?): String {
    val seconds = cacheFor.inWholeSeconds
    if (seconds <= 0) return NO_STORE
    if (requestedVersion == version) return IMMUTABLE
    return "public, max-age=$seconds"
}

/**
 * 1 年。`immutable` を見ない側でも取り直しに来なくなるだけの長さ
 */
private const val IMMUTABLE = "public, max-age=31536000, immutable"
private const val CACHE_CONTROL_HEADER = "Cache-Control"
private const val NO_STORE = "no-store"
private const val CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options"
private const val CONTENT_TYPE_OPTIONS = "nosniff"
