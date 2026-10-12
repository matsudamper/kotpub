package net.matsudamper.activitypub.inbox

import net.matsudamper.activitypub.actor.ActorDirectory
import net.matsudamper.activitypub.http.EndpointResponse
import net.matsudamper.activitypub.http.HttpStatusCodes
import net.matsudamper.activitypub.http.RequestHeaders
import net.matsudamper.activitypub.httpsignature.SignedRequest

/**
 * inbox エンドポイント。相手のサーバーからアクティビティが POST されてくる。
 *
 * ここでやるのは HTTP との変換だけ。宛先のアクターを引き当て、ボディを受け取り、
 * [InboxService] に渡して、返ってきた結果を status に直す。中身を信用してよいかの
 * 判断と、種類ごとの処理は [InboxService] にある。
 *
 * 返す status は次のとおり。
 *
 * - 202 Accepted: 署名が通った。中身の処理の成否は含めない。受信を止めているドメインから届いたときも返す
 * - 400 Bad Request: ボディが JSON として読めない
 * - 401 Unauthorized: 署名が無い、通らない、`actor` と署名者が違う
 * - 404 Not Found: そのアクターがいない（アカウントごとの inbox だけ）
 * - 413 Content Too Large: ボディが大きすぎる
 *
 * 404 と 413 だけがここでの判断になる。どちらも署名を検証する前、
 * ボディを受け取り切る前に決まるので、[InboxService] には渡せない。
 */
class InboxEndpoint(
    private val directory: ActorDirectory,
    private val service: InboxService,
) {
    /**
     * `/users/{username}/inbox`
     */
    suspend fun receiveForAccount(
        username: String?,
        request: IncomingInboxRequest,
    ): EndpointResponse {
        val urls = directory.resolve(username)
            ?: return text(HttpStatusCodes.NOT_FOUND, "アクターが見つからない: $username")

        return receive(recipient = InboxRecipient.Account(urls), request = request)
    }

    /**
     * [net.matsudamper.activitypub.actor.ActorUrls.SHARED_INBOX_PATH]
     */
    suspend fun receiveShared(request: IncomingInboxRequest): EndpointResponse =
        receive(recipient = InboxRecipient.Shared, request = request)

    private suspend fun receive(
        recipient: InboxRecipient,
        request: IncomingInboxRequest,
    ): EndpointResponse {
        // 読む前に長さで弾く。読んでから確かめても、その時点で受け取り終えている
        val declaredLength = request.headers[CONTENT_LENGTH_HEADER]?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_BODY_BYTES) {
            return text(HttpStatusCodes.PAYLOAD_TOO_LARGE, "ボディが大きすぎる")
        }

        val requestBodyBytes = request.readBody(MAX_BODY_BYTES)
        if (requestBodyBytes.size > MAX_BODY_BYTES) {
            return text(HttpStatusCodes.PAYLOAD_TOO_LARGE, "ボディが大きすぎる")
        }

        val signedRequest =
            SignedRequest(
                method = request.method,
                requestTarget = request.requestTarget,
                headers = request.headers,
                body = requestBodyBytes,
            )

        // 落ちた理由は相手に返さない。どこで落ちたかを教えると通る形を探す助けになるので、
        // 理由はサービス側がログに出し、ここには status しか渡ってこない
        return when (service.receive(recipient = recipient, request = signedRequest)) {
            is InboxResult.Unauthorized -> text(HttpStatusCodes.UNAUTHORIZED, "署名を検証できなかった")
            is InboxResult.BadRequest -> text(HttpStatusCodes.BAD_REQUEST, "ボディを読めなかった")
            is InboxResult.Accepted -> text(HttpStatusCodes.ACCEPTED, "")
        }
    }

    private fun text(
        status: Int,
        text: String,
    ): EndpointResponse = EndpointResponse.text(status = status, text = text, headers = mapOf())
}

/**
 * inbox に届いたリクエスト。
 *
 * @param requestTarget パスとクエリ。送信側が署名したのはリクエストラインの綴りそのものなので、
 *   パスを組み直さずにそのまま渡すこと。末尾やクエリの差で署名が合わなくなる
 * @param readBody ボディを読む。`Content-Length` で弾けなかったときだけ呼ばれる。
 *   渡した上限を 1 バイト超えるところまでで読むのを止めること。`Content-Length` の無い
 *   chunked のボディを全部読むと、署名を検証する前に誰でもメモリを使わせられる。
 *   `Digest` はバイト列に対して計算されているので、文字列にせずそのまま返すこと
 */
class IncomingInboxRequest(
    val method: String,
    val requestTarget: String,
    val headers: RequestHeaders,
    val readBody: suspend (maxBytes: Int) -> ByteArray,
)

/**
 * 受け取るボディの上限。
 *
 * アクティビティは大きくても数十 KB にしかならない。上限が無いと、
 * 署名を検証する前の段階でメモリを食い潰させられる。
 */
private const val MAX_BODY_BYTES = 1024 * 1024
private const val CONTENT_LENGTH_HEADER = "Content-Length"
