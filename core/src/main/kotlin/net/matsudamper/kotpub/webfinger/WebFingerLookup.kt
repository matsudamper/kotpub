package net.matsudamper.kotpub.webfinger

import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.http.MediaType
import net.matsudamper.kotpub.json.AppJson
import org.slf4j.LoggerFactory

/**
 * `@alice@example.com` のような acct から、相手のアクターの id を引く。
 *
 * アクター文書の取得は [net.matsudamper.kotpub.actor.RemoteActors] が受け持つ。
 * acct からアクターの URL を得る手段は WebFinger しか無いので、ここはその 1 ホップ目だけを扱う。
 */
interface WebFingerLookup {
    /**
     * @param acct `@alice@example.com`、`alice@example.com`、`acct:alice@example.com` のどれでもよい
     */
    suspend fun findActorId(acct: String): WebFingerResult
}

/**
 * [WebFingerLookup.findActorId] の結果。検索した人に「そのアカウントは無い」と
 * 「相手のサーバーに今はつながらない」を言い分けられるように分ける
 */
sealed interface WebFingerResult {
    data class Found(val actorId: String) : WebFingerResult

    /** acct として読めない入力。問い合わせていない */
    data object InvalidAcct : WebFingerResult

    /** 相手が無いと答えた、または応答からアクターの id を読み取れない。問い合わせ直しても同じ結果になる */
    data object NotFound : WebFingerResult

    /** つながらない、相手が 5xx や 429 を返したなど。時間をおけば引ける見込みがある */
    data object Unavailable : WebFingerResult
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
    private val logger = LoggerFactory.getLogger(HttpWebFingerLookup::class.java)

    override suspend fun findActorId(acct: String): WebFingerResult {
        val account = Acct.parse(acct) ?: return WebFingerResult.InvalidAcct

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
            } catch (e: Exception) {
                logger.warn("WebFinger の取得に失敗した: $acct", e)
                return WebFingerResult.Unavailable
            }

        if (!HttpStatusCodes.isSuccess(response.status)) {
            if (isTemporaryFailure(response.status)) {
                logger.warn("WebFinger の相手が今は応じない: $acct status=${response.status}")
                return WebFingerResult.Unavailable
            }
            logger.info("WebFinger でアカウントが見つからなかった: $acct status=${response.status}")
            return WebFingerResult.NotFound
        }
        if (HttpUrl.parseHttps(response.finalUrl) == null) {
            logger.warn("WebFinger が https ではない URL に移ったので捨てる: $acct → ${response.finalUrl}")
            return WebFingerResult.NotFound
        }

        val body = checkNotNull(response.body) { "成功応答なのに本文を読んでいない: $acct" }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            logger.warn("WebFinger の本文を読めなかった: $acct", failure)
            return WebFingerResult.Unavailable
        }
        if (body.size > MAX_BODY_BYTES) {
            logger.warn("WebFinger の本文が大きすぎるので捨てる: $acct")
            return WebFingerResult.NotFound
        }

        val document =
            runCatching {
                AppJson.decodeFromString(WebFingerResponse.serializer(), body.toString(Charsets.UTF_8))
            }.getOrElse { failure ->
                logger.warn("WebFinger の応答を読めなかった: $acct", failure)
                return WebFingerResult.NotFound
            }

        val actorId = document.links
            .filter { it.rel == WebFingerLink.REL_SELF }
            .filter { link -> link.type?.let { MediaType.parseWithoutParameters(it) } in actorContentTypes }
            .firstNotNullOfOrNull { link -> link.href?.takeIf { HttpUrl.parseHttps(it) != null } }
        if (actorId == null) {
            logger.warn("WebFinger の応答に https のアクター文書へのリンクが無い: $acct")
            return WebFingerResult.NotFound
        }
        return WebFingerResult.Found(actorId)
    }

    private fun isTemporaryFailure(status: Int): Boolean =
        status >= 500 || status == HttpStatusCodes.TOO_MANY_REQUESTS || status == HttpStatusCodes.REQUEST_TIMEOUT

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
