package net.matsudamper.kotpub.http

/**
 * 相手のサーバーから文書を取得できなかった理由。
 *
 * 取得結果の失敗側に載せて呼び出し側へ返す。どう記録するか、利用者に何を見せるかは呼び出し側が決める
 *
 * @property url 取得しようとした URL
 */
sealed interface RemoteFetchFailure {
    val url: String

    /** https の URL ではないので取得しなかった */
    data class NotHttps(override val url: String) : RemoteFetchFailure

    /** 取得してよいホストではないので取得しなかった */
    data class DisallowedHost(override val url: String) : RemoteFetchFailure

    /** 直前に取得したばかりなので、間隔を空けるために取得しなかった */
    data class Throttled(override val url: String) : RemoteFetchFailure

    /** つながらない、タイムアウトしたなど、応答を受け取れなかった */
    data class RequestFailed(
        override val url: String,
        val cause: Throwable,
    ) : RemoteFetchFailure

    data class UnsuccessfulStatus(
        override val url: String,
        val status: Int,
    ) : RemoteFetchFailure

    /** リダイレクトで、取得してよくない URL に移った */
    data class RedirectedToDisallowedUrl(
        override val url: String,
        val finalUrl: String,
    ) : RemoteFetchFailure

    data class BodyUnreadable(
        override val url: String,
        val cause: Throwable,
    ) : RemoteFetchFailure

    data class BodyTooLarge(
        override val url: String,
        val maxBytes: Int,
    ) : RemoteFetchFailure

    /**
     * 取得できたが、文書として読めない、または条件を満たさない
     *
     * @param detail 何が足りなかったか
     * @param cause 読もうとして投げられた例外。条件を満たさなかっただけなら null
     */
    data class InvalidDocument(
        override val url: String,
        val detail: String,
        val cause: Throwable? = null,
    ) : RemoteFetchFailure
}
