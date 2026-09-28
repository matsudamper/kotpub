package net.matsudamper.activitypub.http

/**
 * このモジュールが返す、または相手から受けて判断に使う status
 */
object HttpStatusCodes {
    const val OK = 200
    const val ACCEPTED = 202
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val NOT_FOUND = 404
    const val REQUEST_TIMEOUT = 408
    const val GONE = 410
    const val PAYLOAD_TOO_LARGE = 413
    const val TOO_MANY_REQUESTS = 429
    const val NOT_IMPLEMENTED = 501

    fun isSuccess(status: Int): Boolean = status in 200..299
}
