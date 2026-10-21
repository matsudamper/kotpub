package net.matsudamper.kotpub.webfinger

import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.http.MediaType
import net.matsudamper.kotpub.json.AppJson

/**
 * `@alice@example.com` のような acct から、相手のアクターの id を引く。
 *
 * アクター文書の取得は [net.matsudamper.kotpub.actor.RemoteActors] が受け持つ。
 * acct からアクターの URL を得る手段は WebFinger しか無いので、ここはその 1 ホップ目だけを扱う。
 */
interface WebFingerLookup {
    /**
     * 見つからない・読めない・acct として読めないときは null。
     *
     * @param acct `@alice@example.com`、`alice@example.com`、`acct:alice@example.com` のどれでもよい
     */
    suspend fun findActorId(acct: String): String?
}

/**
 * WebFinger を相手のサーバーへ取得しに行く [WebFingerLookup]。
 *
 * 取得先のホストは検索した人が入力したもので、こちらが選べない。任意のホストへ GET させられる
 * 経路なので、次を守る。
 *
 * - https のみ
 * - 宛先が公開アドレスであることは [client] が接続の前に確かめる
 * - 返ってきたアクターの id も https に限る。続けてその URL を取得しに行くため
 * - 大きすぎる応答は読まない
 *
 * リダイレクトで別のホストに移るのは許す。Mastodon は acct のドメインと
 * Web のドメインを分けられ、acct のドメインの WebFinger は Web のドメインへリダイレクトする。
 *
 * @param client 閉じるのは呼び出し側。アクター文書の取得と同じクライアントを使い回せるようにする
 */
class HttpWebFingerLookup(
    private val client: ActivityPubHttpClient,
) : WebFingerLookup {
    override suspend fun findActorId(acct: String): String? {
        val account = Acct.parse(acct) ?: return null

        val response =
            try {
                client.get(
                    url = account.webFingerUrl(),
                    headers = mapOf(ACCEPT_HEADER to "${ActivityPubContentTypes.JrdJson}, application/json"),
                    maxBodyBytes = MAX_BODY_BYTES,
                    readsBody = { status, finalUrl ->
                        HttpStatusCodes.isSuccess(status) && HttpUrl.parseHttps(finalUrl) != null
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }

        if (!HttpStatusCodes.isSuccess(response.status)) return null
        if (HttpUrl.parseHttps(response.finalUrl) == null) return null

        val body = response.body?.getOrNull() ?: return null
        if (body.size > MAX_BODY_BYTES) return null

        val document =
            runCatching {
                AppJson.decodeFromString(WebFingerResponse.serializer(), body.toString(Charsets.UTF_8))
            }.getOrNull() ?: return null

        return document.links
            .filter { it.rel == WebFingerLink.REL_SELF }
            .filter { link -> link.type?.let { MediaType.parseWithoutParameters(it) } in actorContentTypes }
            .firstNotNullOfOrNull { link -> link.href?.takeIf { HttpUrl.parseHttps(it) != null } }
    }

    /**
     * @param host ポートが付いていればそれも含む
     */
    private class Acct(
        val username: String,
        val host: String,
    ) {
        fun webFingerUrl(): String {
            val resource = URLEncoder.encode("acct:$username@$host", Charsets.UTF_8)
            return "https://$host/.well-known/webfinger?resource=$resource"
        }

        companion object {
            /**
             * ホストに URL の区切りが入っていると、組み立てた URL のパスやクエリが
             * 入力した人の決めたものになるので読まない
             */
            private val forbiddenHostCharacters = charArrayOf('/', '?', '#', '@', '\\')

            fun parse(raw: String): Acct? {
                val acct = raw.trim().removePrefix("acct:").removePrefix("@")
                val username = acct.substringBefore('@', missingDelimiterValue = "")
                val host = acct.substringAfter('@', missingDelimiterValue = "")
                if (username.isEmpty() || host.isEmpty()) return null
                if (username.any { it.isWhitespace() }) return null
                if (host.any { it.isWhitespace() || it in forbiddenHostCharacters }) return null

                // IDN のホストは、接続するときと同じ punycode で問い合わせる。
                // 全角の `／` などは正規化で ASCII の区切りになるので、正規化した後も確かめる
                val url = HttpUrl.parseHttps("https://$host/") ?: return null
                if (url.host.any { it in forbiddenHostCharacters }) return null
                val normalizedHost = if (url.port == -1) url.host else "${url.host}:${url.port}"
                return Acct(username = username, host = normalizedHost)
            }
        }
    }

    private companion object {
        const val ACCEPT_HEADER = "Accept"

        /**
         * 読み込む応答の上限。WebFinger の応答はリンクが数個並ぶだけで数 KB にしかならない
         */
        const val MAX_BODY_BYTES = 64 * 1024

        /**
         * `rel: "self"` のうち、アクター文書を指すものの型。型の無いものや別の型のものは、
         * 取得してもアクター文書として読めない
         */
        val actorContentTypes: Set<MediaType?> = setOf(ActivityPubContentTypes.ActivityJson, ActivityPubContentTypes.LdJson)
    }
}
