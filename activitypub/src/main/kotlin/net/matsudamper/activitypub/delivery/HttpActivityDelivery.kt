package net.matsudamper.activitypub.delivery

import java.io.Closeable
import kotlin.coroutines.cancellation.CancellationException
import net.matsudamper.activitypub.activitypub.ActivityPubContentTypes
import net.matsudamper.activitypub.actor.ActorKey
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.http.ActivityPubHttpClient
import net.matsudamper.activitypub.http.HttpStatusCodes
import net.matsudamper.activitypub.http.HttpUrl
import net.matsudamper.activitypub.httpsignature.HttpSignatureSigner

/**
 * 実際に相手のサーバーへ POST する [ActivityDelivery]。
 *
 * 署名対象のヘッダは自分で組み立てて明示的に載せる。通信の実装に任せて後から
 * 足された値と、署名したときの値が食い違うと、相手側では「署名が一致しない」
 * としか見えず原因が追えなくなる。
 *
 * @param actorKey 署名に使う秘密鍵。いまはアクターが何人いても鍵は 1 本で、
 *   使い捨てアクターも同じ鍵を共有する。アクターごとの鍵になるのは Phase 6
 * @param client 閉じるのはこのクラスの [close] が受け持つ
 */
class HttpActivityDelivery(
    private val actorKey: ActorKey,
    private val client: ActivityPubHttpClient,
    private val signer: HttpSignatureSigner = HttpSignatureSigner(),
) : ActivityDelivery,
    Closeable {
    override suspend fun deliver(
        inbox: String,
        sender: ActorUrls,
        body: ByteArray,
    ): DeliveryResult {
        val url = HttpUrl.parse(inbox)
            // 読めない URL は次に読めるようになることが無いので送り直さない
            ?: return DeliveryResult.Failed(reason = "inbox の URL を読めない: $inbox", retryable = false)

        val headers =
            signer.sign(
                keyId = sender.publicKeyId,
                privateKey = actorKey.privateKey,
                method = "POST",
                // 相手はリクエストラインの綴りで署名文字列を組み直す。
                // パスを組み立て直すとクエリや末尾の差で合わなくなる
                requestTarget = requestTarget(url),
                host = hostHeader(url),
                body = body,
            )

        val response =
            runCatching {
                client.post(
                    url = inbox,
                    headers = headers + (CONTENT_TYPE_HEADER to ActivityPubContentTypes.ActivityJson.toString()),
                    body = body,
                )
            }.getOrElse { error ->
                // runCatching は Throwable を拾うので、呼び出し元が消えた合図まで
                // 配信の失敗に化ける。化けると送れていない記事が投稿済みとして残る
                if (error is CancellationException) throw error
                // 届いていないので、相手が戻れば送れる
                return DeliveryResult.Failed(reason = "POST に失敗した: $inbox ${error.message}", retryable = true)
            }

        if (!HttpStatusCodes.isSuccess(response.status)) {
            return DeliveryResult.Failed(
                reason = "相手が受け取らなかった: $inbox ${response.status}",
                retryable = isRetryable(response.status),
            )
        }

        return DeliveryResult.Delivered
    }

    override fun close() {
        client.close()
    }

    private companion object {
        const val CONTENT_TYPE_HEADER = "Content-Type"

        /**
         * 相手の応答が、送り直せば届きうるものか。
         *
         * 4xx は相手が「受け取らない」と決めた応答なので送り直さない。ただし
         * 401 は鍵の入れ替えの途中、408 と 429 は詰まっているだけで、どちらも後なら通る。
         * 501 は実装していないという意味なので送り直さない。
         * Mastodon 自身もこの区切りで捨てているので、こちらも合わせる
         */
        fun isRetryable(status: Int): Boolean {
            if (status == HttpStatusCodes.NOT_IMPLEMENTED) return false
            if (status !in 400..499) return true

            return status in RETRYABLE_CLIENT_ERRORS
        }

        val RETRYABLE_CLIENT_ERRORS = setOf(
            HttpStatusCodes.UNAUTHORIZED,
            HttpStatusCodes.REQUEST_TIMEOUT,
            HttpStatusCodes.TOO_MANY_REQUESTS,
        )

        /**
         * 署名した `(request-target)` と実際に送るリクエストラインを揃える。
         * パスが空なら、リクエストラインに載るのは `/`
         */
        fun requestTarget(url: HttpUrl): String {
            val path = url.rawPath.ifEmpty { "/" }
            val query = url.rawQuery
            return if (query.isNullOrEmpty()) path else "$path?$query"
        }

        /**
         * `Host` に載せる値。既定ポート（https の 443）ならポート番号は付けない。
         * 付いていると相手が組み立てる署名文字列と食い違う。
         */
        fun hostHeader(url: HttpUrl): String =
            if (url.port == -1 || url.port == DEFAULT_PORTS[url.scheme]) {
                url.host
            } else {
                "${url.host}:${url.port}"
            }

        val DEFAULT_PORTS = mapOf("https" to 443, "http" to 80)
    }
}
