package net.matsudamper.kotpub.httpsignature

import net.matsudamper.kotpub.http.HttpUrl

/**
 * 署名に入れる `(request-target)` と `Host` を、実際に送るリクエストラインに揃えて作る
 */
internal object SignedRequestLine {
    private val DEFAULT_PORTS = mapOf("https" to 443, "http" to 80)

    /**
     * 相手はリクエストラインの文字列で署名文字列を組み直す。
     * パスを組み立て直すとクエリや末尾の差で合わなくなる。
     * パスが空なら、リクエストラインに載るのは `/`
     */
    fun requestTarget(url: HttpUrl): String {
        val path = url.rawPath.ifEmpty { "/" }
        val query = url.rawQuery
        return if (query.isNullOrEmpty()) path else "$path?$query"
    }

    /**
     * 既定ポート（https の 443）ならポート番号は付けない。
     * 付いていると相手が組み立てる署名文字列と食い違う。
     */
    fun hostHeader(url: HttpUrl): String =
        if (url.port == -1 || url.port == DEFAULT_PORTS[url.scheme]) {
            url.host
        } else {
            "${url.host}:${url.port}"
        }
}
