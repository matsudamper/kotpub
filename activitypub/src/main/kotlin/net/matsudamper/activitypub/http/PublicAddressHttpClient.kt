package net.matsudamper.activitypub.http

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 公開アドレスにだけ接続する [ActivityPubHttpClient]。
 *
 * 接続のたびに宛先が公開アドレスであることを確かめてから、そのとき解決したアドレスへ繋ぐ。
 * 応答が返ったあとに URL を見ても、その時点で接続は済んでいる。
 * 名前を引き直してから繋ぐと、検査のあとだけ内部アドレスを返す名前解決ですり抜けられる。
 * 汎用の HTTP クライアントに任せると名前解決とリダイレクトの追跡が中に隠れて検査できないので、
 * 接続から応答の読み取りまで自前で行う。
 *
 * [create] で作る。手元のサーバーと連合を試すときだけ [createAllowingPrivateAddresses] を使う。既定は拒否。
 *
 * [ActivityPubHttpClient] の約束に加えて次を守る。
 *
 * - scheme は http と https だけ。それ以外と、公開アドレスではない宛先は [OutboundAddressRejectedException]
 * - 接続開始から応答が終わるまで 10 秒。接続だけは 5 秒で切る。
 *   1 バイトずつ送られても、そのたびに待ち直さない
 * - 名前解決が複数の公開アドレスを返したら、その順に繋ぐ。届かなかったときだけ次を試す。
 *   応答が返ったあとは次へ進まない。POST を送り直すと相手に二重に届く。
 *   試行はすべて、接続開始からの 10 秒に収める
 * - GET のリダイレクトは自動では追わない。1 ホップごとに同じ検査をしてから次へ進む。
 *   https で始まった取得が http に落ちるリダイレクトは追わない。平文だと経路上で文書を差し替えられる。
 *   追うのは [MAX_GET_REDIRECTS] 回まで
 */
class PublicAddressHttpClient internal constructor(
    private val lookup: HostAddressLookup,
    private val transport: PinnedHttpTransport,
    private val allowsPrivateAddresses: Boolean,
) : ActivityPubHttpClient {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        readsBody: (status: Int, finalUrl: String) -> Boolean,
    ): ActivityPubClientResponse =
        withContext(Dispatchers.IO) {
            getAllowingRedirects(
                url = url,
                headers = headers,
                maxBodyBytes = maxBodyBytes,
                readsBody = readsBody,
                remainingRedirects = MAX_GET_REDIRECTS,
            )
        }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): ActivityPubPostResponse =
        withContext(Dispatchers.IO) {
            val response =
                exchange(
                    url = url,
                    method = "POST",
                    headers = headers,
                    body = body,
                    maxBodyBytes = 0,
                    readsBody = { _, _ -> false },
                )
            ActivityPubPostResponse(status = response.status)
        }

    override fun close() {
        transport.close()
    }

    private fun getAllowingRedirects(
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        readsBody: (status: Int, finalUrl: String) -> Boolean,
        remainingRedirects: Int,
    ): ActivityPubClientResponse {
        val response =
            exchange(
                url = url,
                method = "GET",
                headers = headers,
                body = ByteArray(0),
                maxBodyBytes = maxBodyBytes,
                readsBody = { status, responseHeaders ->
                    val follows = redirectLocation(status, responseHeaders) != null && remainingRedirects > 0
                    if (follows) false else readsBody(status, url)
                },
            )
        val location = redirectLocation(response.status, response.headers)
        if (location != null && remainingRedirects > 0) {
            return getAllowingRedirects(
                url = resolveRedirect(url, location),
                headers = headersForNextHop(headers),
                maxBodyBytes = maxBodyBytes,
                readsBody = readsBody,
                remainingRedirects = remainingRedirects - 1,
            )
        }
        return ActivityPubClientResponse(status = response.status, finalUrl = url, body = response.body)
    }

    private fun exchange(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray,
        maxBodyBytes: Int,
        readsBody: (status: Int, headers: ResponseHeaders) -> Boolean,
    ): PinnedHttpResponse {
        val destination = HttpUrl.parse(url) ?: throw OutboundAddressRejectedException("接続先の URL を読めない")
        if (!destination.isHttpOrHttps) {
            throw OutboundAddressRejectedException("http と https 以外には接続しない: ${destination.scheme}")
        }
        val addresses = checkedAddresses(destination)
        return transport.exchange(
            request =
            PinnedHttpRequest(
                method = method,
                url = destination,
                addresses = addresses,
                headers = headers,
                body = body,
            ),
            readsBody = readsBody,
            maxBodyBytes = maxBodyBytes,
        )
    }

    /**
     * 解決した全部を見てから、そのリストの順に繋ぐ。
     * リストの外のアドレスへは繋がない
     */
    private fun checkedAddresses(destination: HttpUrl): List<InetAddress> {
        val addresses = lookup.lookup(destination.host)
        if (addresses.isEmpty()) throw UnknownHostException(destination.host)
        if (allowsPrivateAddresses) return addresses

        val blocked = addresses.filterNot { PublicInetAddresses.isPublic(it) }
        if (blocked.isNotEmpty()) {
            throw OutboundAddressRejectedException(
                "公開アドレスではない宛先には接続しない: ${destination.host} " +
                    blocked.joinToString(" ") { it.hostAddress },
            )
        }
        return addresses
    }

    private fun redirectLocation(
        status: Int,
        headers: ResponseHeaders,
    ): String? {
        if (status !in REDIRECT_STATUSES) return null
        return headers.first("Location")?.takeIf { it.isNotEmpty() }
    }

    private fun resolveRedirect(
        current: String,
        location: String,
    ): String {
        val resolved =
            runCatching { URI(current).resolve(location) }.getOrNull()
                ?: throw OutboundAddressRejectedException("リダイレクト先の URL を読めない")
        if (!resolved.isAbsolute) throw OutboundAddressRejectedException("リダイレクト先の URL を読めない")
        val next = resolved.toASCIIString()
        val currentUrl = HttpUrl.parse(current)
        val nextUrl = HttpUrl.parse(next) ?: throw OutboundAddressRejectedException("リダイレクト先の URL を読めない")
        if (currentUrl != null && currentUrl.isHttps && !nextUrl.isHttps) {
            throw OutboundAddressRejectedException("https からのリダイレクト先は https に限る")
        }
        return next
    }

    private fun headersForNextHop(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { name ->
            !name.equals("Host", ignoreCase = true) &&
                !name.equals("Content-Length", ignoreCase = true) &&
                !name.equals("Connection", ignoreCase = true)
        }

    companion object {
        /**
         * GET でリダイレクトを追う回数。これを超えた応答は、その status のまま返す
         */
        internal const val MAX_GET_REDIRECTS = 5

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        fun create(): PublicAddressHttpClient = create(allowsPrivateAddresses = false)

        /**
         * ループバックやプライベートアドレスにも接続する。
         * 開発で自分のサーバーと連合を試すとき以外は使わない
         */
        fun createAllowingPrivateAddresses(): PublicAddressHttpClient = create(allowsPrivateAddresses = true)

        private fun create(allowsPrivateAddresses: Boolean): PublicAddressHttpClient =
            PublicAddressHttpClient(
                lookup = SystemHostAddressLookup,
                transport = SocketPinnedHttpTransport(),
                allowsPrivateAddresses = allowsPrivateAddresses,
            )
    }
}
