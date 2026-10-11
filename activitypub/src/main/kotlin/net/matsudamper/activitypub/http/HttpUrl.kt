package net.matsudamper.activitypub.http

import java.net.IDN
import java.net.URI

/**
 * 絶対 URL のうち、宛先を決める部分だけ。ホストの無いものは読めない扱いにする
 */
internal class HttpUrl private constructor(
    val scheme: String,
    val host: String,
    /**
     * 書かれていなければ -1
     */
    val port: Int,
    val rawPath: String,
    val rawQuery: String?,
) {
    val isHttps: Boolean get() = scheme == HTTPS

    val isHttpOrHttps: Boolean get() = scheme == HTTP || scheme == HTTPS

    fun isSameHost(other: HttpUrl): Boolean = host.equals(other.host, ignoreCase = true)

    /**
     * リクエストラインに載せるパスとクエリ。空のパスは `/`。
     * 署名した `(request-target)` とここが違うと、相手側の検証が通らない
     */
    fun requestTarget(): String {
        val path = rawPath.ifEmpty { "/" }
        val query = rawQuery
        return if (query.isNullOrEmpty()) path else "$path?$query"
    }

    /**
     * `Host` に載る値。既定ポートなら番号は付けない。
     * 付いていると相手が組み立てる署名文字列と食い違う。
     * IPv6 のホストは `URI` が括弧付きで返すので、ここでは足さない
     */
    fun hostHeader(): String {
        val defaultPort = when (scheme) {
            HTTPS -> HTTPS_PORT
            HTTP -> HTTP_PORT
            else -> -1
        }
        return if (port == -1 || port == defaultPort) host else "$host:$port"
    }

    /**
     * 書かれていなければスキームの既定ポート
     */
    fun effectivePort(): Int = if (port != -1) {
        port
    } else if (isHttps) {
        HTTPS_PORT
    } else {
        HTTP_PORT
    }

    companion object {
        private const val HTTP = "http"
        private const val HTTPS = "https"
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443

        fun parse(raw: String): HttpUrl? {
            val iri = runCatching { URI(raw) }.getOrNull() ?: return null
            val scheme = iri.scheme?.lowercase() ?: return null
            val authority = iri.hostAndPort() ?: return null

            // Unicode のホスト名は、送るときと同じ punycode にする。
            // Host ヘッダは署名に入るので、送るときの綴りと揃っていないと検証が通らない
            val host = runCatching { IDN.toASCII(authority.host) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: return null

            // パスやクエリに percent-encoding されていない非 ASCII 文字があると、rawPath はそれを
            // そのまま返す。送るときは UTF-8 で符号化されるので、署名する綴りもそちらに揃える
            val ascii = runCatching { URI(iri.toASCIIString()) }.getOrNull() ?: return null

            return HttpUrl(
                scheme = scheme,
                host = host,
                port = authority.port,
                rawPath = ascii.rawPath.orEmpty(),
                rawQuery = ascii.rawQuery,
            )
        }

        fun parseHttps(raw: String): HttpUrl? = parse(raw)?.takeIf { it.isHttps }
    }
}

private class HostAndPort(
    val host: String,
    /**
     * 書かれていなければ -1
     */
    val port: Int,
)

/**
 * `URI` は ASCII でないホスト名をホストとして読まず、host が null になる。
 * その場合は authority から自分で切り出す
 */
private fun URI.hostAndPort(): HostAndPort? {
    val parsedHost = host
    if (parsedHost != null) return HostAndPort(host = parsedHost, port = port)

    val authority = rawAuthority?.substringAfterLast('@') ?: return null
    val portText = authority.substringAfterLast(':', missingDelimiterValue = "")
    if (portText.isEmpty()) return HostAndPort(host = authority.removeSuffix(":"), port = -1)

    val port = portText.toIntOrNull() ?: return null
    return HostAndPort(host = authority.substringBeforeLast(':'), port = port)
}
