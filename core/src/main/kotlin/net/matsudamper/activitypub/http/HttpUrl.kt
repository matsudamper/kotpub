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

    fun isSameHost(other: HttpUrl): Boolean = host.equals(other.host, ignoreCase = true)

    companion object {
        private const val HTTPS = "https"

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
