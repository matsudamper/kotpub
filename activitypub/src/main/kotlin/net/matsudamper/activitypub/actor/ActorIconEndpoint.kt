package net.matsudamper.activitypub.actor

import kotlin.time.Duration
import net.matsudamper.activitypub.http.EndpointResponse
import net.matsudamper.activitypub.http.HttpStatusCodes

/**
 * アクターのプロフィール画像。Actor JSON の `icon` が指す先。
 *
 * 中身は使う側が [ActorIcons] で渡す。相手にこの URL を渡しておくと、
 * 画像の置き場を変えてもアイコンの URL は変わらない。
 * 公開画面から見ても同じオリジンなので、配信元の CORS の設定に左右されない。
 */
class ActorIconEndpoint(
    private val directory: ActorDirectory,
    private val icons: ActorIcons,
) {
    /**
     * `/users/{username}/icon`
     *
     * @param version クエリの `v`。[ActorUrls.icon] が付ける値
     */
    suspend fun get(
        username: String?,
        version: String?,
    ): EndpointResponse {
        // 後からアカウントやアイコンが増えれば同じ URL で出るようになる。
        // 見に来た側が 404 を持っていると、出るようになった後も出ない
        val urls = directory.resolve(username)
            ?: return notFound("アクターが見つからない: $username")

        val icon = icons.find(urls.username)
            ?: return notFound("アイコンが無い: ${urls.username}")

        return EndpointResponse(
            status = HttpStatusCodes.OK,
            contentType = icon.contentType,
            body = icon.bytes,
            headers = mapOf(
                CACHE_CONTROL_HEADER to icon.cacheControl(version),
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
 * アクターのプロフィール画像の引き先。
 *
 * どこから取ってくるかは Actor 側の関心ではないので、
 * [StoredActorNames] と同じく名前を渡して引けることだけを決めておく。
 */
interface ActorIcons {
    /**
     * 名前で引く。アイコンが無いか、取ってこられなければ null。
     *
     * 渡すのは [StoredActorNames] が返した保存側の綴り。
     */
    suspend fun find(username: String): ActorIcon?
}

/**
 * プロフィール画像の中身。
 *
 * @param contentType 取得元が名乗った種類。そのまま返す
 * @param version 取得元の URL から決まる値。要求された値と一致すれば入れ替わらない中身と分かる
 * @param cacheFor 取得元から決まる値が付いていない URL で来たときに持たせる時間。0 なら持たせない
 */
class ActorIcon(
    val bytes: ByteArray,
    val contentType: String,
    val version: String,
    val cacheFor: Duration,
)

/**
 * 見に来た側に持たせる時間。
 *
 * [ActorUrls.icon] と GraphQL が渡す URL には取得元から決まる値が付いている。
 * その値が今置いてあるものと一致していれば、同じ URL の中身は入れ替わらない
 * （入れ替わるときは URL ごと変わる）ので長く持たせる。
 *
 * 付いていない URL は取り込みが入れ替えると中身が変わるので、
 * 配信元が言ってきた時間だけにする。長く持たせると古い画像が出続ける
 */
private fun ActorIcon.cacheControl(requestedVersion: String?): String {
    if (requestedVersion == version) return IMMUTABLE

    val seconds = cacheFor.inWholeSeconds
    if (seconds <= 0) return NO_STORE
    return "public, max-age=$seconds"
}

/** 1 年。`immutable` を見ない側でも取り直しに来なくなるだけの長さ */
private const val IMMUTABLE = "public, max-age=31536000, immutable"
private const val CACHE_CONTROL_HEADER = "Cache-Control"
private const val NO_STORE = "no-store"
private const val CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options"
private const val CONTENT_TYPE_OPTIONS = "nosniff"
