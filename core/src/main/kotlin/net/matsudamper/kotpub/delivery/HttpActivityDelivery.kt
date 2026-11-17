package net.matsudamper.kotpub.delivery

import java.io.Closeable
import kotlin.coroutines.cancellation.CancellationException
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.actor.LocalActor
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.http.NonPublicAddressException
import net.matsudamper.kotpub.httpsignature.HttpSignatureSigner
import net.matsudamper.kotpub.httpsignature.SignedRequestLine

/**
 * 実際に相手のサーバーへ POST する [ActivityDelivery]。
 *
 * 署名対象のヘッダは自分で組み立てて明示的に載せる。通信の実装に任せて後から
 * 足された値と、署名したときの値が食い違うと、相手側では「署名が一致しない」
 * としか見えず原因が追えなくなる。
 *
 * @param client 閉じるのはこのクラスの [close] が受け持つ
 */
class HttpActivityDelivery(
    private val client: ActivityPubHttpClient,
    private val signer: HttpSignatureSigner = HttpSignatureSigner(),
) : ActivityDelivery,
    Closeable {
    override suspend fun deliver(
        inbox: String,
        sender: LocalActor,
        body: ByteArray,
    ): DeliveryResult {
        val url = HttpUrl.parse(inbox)
            // 読めない URL は次に読めるようになることが無いので送り直さない
            ?: return DeliveryResult.Failed(reason = "inbox の URL を読めない: $inbox", retryable = false)

        val headers =
            signer.sign(
                keyId = sender.urls.publicKeyId,
                privateKey = sender.privateKey.privateKey,
                method = "POST",
                requestTarget = SignedRequestLine.requestTarget(url),
                host = SignedRequestLine.hostHeader(url),
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
                if (error is CancellationException) throw error
                // 公開アドレスではない宛先は、送り直しても届くようにならない。
                // HTTP クライアントが包んで投げ直すことがあるので、原因までたどる
                if (generateSequence(error) { it.cause }.any { it is NonPublicAddressException }) {
                    return DeliveryResult.Failed(
                        reason = "公開アドレスではない宛先には送らない: $inbox ${error.message}",
                        retryable = false,
                    )
                }
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
    }
}
