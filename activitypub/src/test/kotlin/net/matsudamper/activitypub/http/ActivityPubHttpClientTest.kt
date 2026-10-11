package net.matsudamper.activitypub.http

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

/**
 * 宛先の検査と、検査したアドレスへの接続。通信そのものは差し替える
 */
class ActivityPubHttpClientTest {
    @Test
    fun `各種の非公開アドレスを拒否する`() {
        val urls =
            listOf(
                "http://127.0.0.1/",
                "http://127.255.255.255/",
                "http://0.0.0.0/",
                "http://0.255.255.255/",
                "http://10.0.0.1/",
                "http://172.16.0.1/",
                "http://172.31.255.255/",
                "http://192.168.1.1/",
                "http://169.254.169.254/",
                "http://100.64.0.1/",
                "http://100.127.255.255/",
                "http://224.0.0.1/",
                "http://239.255.255.255/",
                "http://255.255.255.255/",
                "http://[::1]/",
                "http://[::]/",
                "http://[fe80::1]/",
                "http://[fc00::1]/",
                "http://[fd12::1]/",
                "http://[fec0::1]/",
                "http://[ff02::1]/",
                "http://[::ffff:127.0.0.1]/",
                "http://[::ffff:10.0.0.1]/",
                "http://[::ffff:192.168.0.1]/",
                "http://[::ffff:172.16.0.1]/",
                "http://[::ffff:169.254.169.254]/",
                "http://[::ffff:100.64.0.1]/",
                "http://[::ffff:224.0.0.1]/",
                "http://[::a00:1]/",
                "http://[64:ff9b::a00:1]/",
                "http://[64:ff9b::7f00:1]/",
                "http://[2002:a00:1::]/",
                "http://[2002:a9fe:a9fe::]/",
                "http://192.0.0.1/",
                "http://192.0.2.1/",
                "http://192.88.99.1/",
                "http://198.18.0.1/",
                "http://198.19.255.1/",
                "http://198.51.100.1/",
                "http://203.0.113.1/",
                "http://240.0.0.1/",
                "http://[2001:db8::1]/",
                "http://[2001::1]/",
                "http://[2001:1::4]/",
                "http://[2001:2::1]/",
                "http://[2001:4::1]/",
                "http://[2001:5::1]/",
                "http://[2001:10::1]/",
                "http://[3fff::1]/",
                "http://[100::1]/",
            )

        for (url in urls) {
            val transport = FakeTransport { error("接続した: $url") }
            val client = client(ScriptedLookup(mapOf()), transport, allowsPrivateAddresses = false)
            val failure = runCatching { get(client, url) }.exceptionOrNull()
            assertIs<OutboundAddressRejectedException>(failure, url)
            assertEquals(0, transport.calls.size, url)
        }
    }

    @Test
    fun `公開アドレスは通る`() {
        val urls =
            listOf(
                "http://8.8.8.8/a",
                "http://1.1.1.1/a",
                "http://172.15.255.255/a",
                "http://172.32.0.1/a",
                "http://100.63.255.255/a",
                "http://100.128.0.1/a",
                "http://169.253.1.1/a",
                "http://11.0.0.1/a",
                "https://[2001:4860:4860::8888]/a",
                "http://[2001:1::1]/a",
                "http://[2001:1::2]/a",
                "http://[2001:1::3]/a",
                "http://[2001:3::1]/a",
                "http://[2001:4:112::1]/a",
                "http://[2001:20::1]/a",
                "http://[2001:30::1]/a",
                "http://[::ffff:8.8.8.8]/a",
                "http://[64:ff9b::808:808]/a",
                "http://[2002:808:808::]/a",
            )

        for (url in urls) {
            val transport = okTransport()
            val client = client(ScriptedLookup(mapOf()), transport, allowsPrivateAddresses = false)
            val response = get(client, url)
            assertEquals(200, response.status, url)
            assertEquals(1, transport.calls.size, url)
        }
    }

    @Test
    fun `検査したアドレスにそのまま接続する`() {
        val pinned = InetAddress.getByName("8.8.8.8")
        val other = InetAddress.getByName("1.1.1.1")
        val lookup = ScriptedLookup(mapOf("public.example" to listOf(pinned, other)))
        val transport = okTransport()
        val client = client(lookup, transport, allowsPrivateAddresses = false)

        val response = get(client, "https://public.example/users/a")

        assertEquals(200, response.status)
        assertEquals("ok", response.body?.getOrThrow()?.decodeToString())
        assertEquals(listOf(pinned, other), transport.calls.single().addresses)
        assertEquals(listOf("public.example"), lookup.lookedUp)
    }

    @Test
    fun `リダイレクト先が非公開ならそのホップで止まる`() {
        val pinned = InetAddress.getByName("8.8.8.8")
        val lookup = ScriptedLookup(mapOf("example.com" to listOf(pinned)))
        val transport =
            FakeTransport {
                PinnedHttpResponse(
                    status = 302,
                    headers = ResponseHeaders(listOf("Location" to "http://127.0.0.1/secret")),
                    body = null,
                )
            }
        val client = client(lookup, transport, allowsPrivateAddresses = false)

        val failure = runCatching { get(client, "http://example.com/actor") }.exceptionOrNull()

        assertIs<OutboundAddressRejectedException>(failure)
        assertEquals(1, transport.calls.size)
        assertSame(pinned, transport.calls.single().addresses.single())
        assertEquals(listOf("example.com", "127.0.0.1"), lookup.lookedUp)
    }

    @Test
    fun `httpsからのリダイレクト先がhttpならそのホップで止まる`() {
        val pinned = InetAddress.getByName("8.8.8.8")
        val lookup = ScriptedLookup(mapOf("example.com" to listOf(pinned)))
        val transport =
            FakeTransport {
                PinnedHttpResponse(
                    status = 301,
                    headers = ResponseHeaders(listOf("Location" to "http://example.com/next")),
                    body = null,
                )
            }
        val client = client(lookup, transport, allowsPrivateAddresses = false)

        val failure = runCatching { get(client, "https://example.com/actor") }.exceptionOrNull()

        assertIs<OutboundAddressRejectedException>(failure)
        assertEquals(1, transport.calls.size)
        assertSame(pinned, transport.calls.single().addresses.single())
    }

    @Test
    fun `名前解決の結果に非公開アドレスが混ざっていたら拒否する`() {
        val lookup =
            ScriptedLookup(
                mapOf(
                    "example.com" to
                        listOf(
                            InetAddress.getByName("8.8.8.8"),
                            InetAddress.getByName("10.0.0.1"),
                        ),
                ),
            )
        val transport = FakeTransport { error("接続した") }
        val client = client(lookup, transport, allowsPrivateAddresses = false)

        val failure = runCatching { get(client, "https://example.com/actor") }.exceptionOrNull()

        assertIs<OutboundAddressRejectedException>(failure)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `公開アドレスへのリダイレクトは検査してから追う`() {
        val first = InetAddress.getByName("8.8.8.8")
        val second = InetAddress.getByName("1.1.1.1")
        val lookup =
            ScriptedLookup(
                mapOf(
                    "example.com" to listOf(first),
                    "other.example" to listOf(second),
                ),
            )
        val transport =
            FakeTransport { request ->
                if (request.url.host == "example.com") {
                    PinnedHttpResponse(
                        status = 302,
                        headers = ResponseHeaders(listOf("Location" to "https://other.example/users/a")),
                        body = null,
                    )
                } else {
                    PinnedHttpResponse(
                        status = 200,
                        headers = ResponseHeaders(listOf()),
                        body = Result.success("doc".encodeToByteArray()),
                    )
                }
            }
        val client = client(lookup, transport, allowsPrivateAddresses = false)

        val response =
            runBlocking {
                client.get(
                    url = "https://example.com/users/a",
                    headers = mapOf("Host" to "example.com", "Accept" to "application/activity+json"),
                    maxBodyBytes = 64,
                ) { _, _ -> true }
            }

        assertEquals(200, response.status)
        assertEquals("https://other.example/users/a", response.finalUrl)
        assertEquals("doc", response.body?.getOrThrow()?.decodeToString())
        assertSame(first, transport.calls[0].addresses.single())
        assertSame(second, transport.calls[1].addresses.single())
        assertEquals("example.com", transport.calls[0].headers["Host"])
        assertEquals("application/activity+json", transport.calls[1].headers["Accept"])
        assertEquals(null, transport.calls[1].headers["Host"])
    }

    @Test
    fun `リダイレクトは上限までしか追わない`() {
        val lookup = ScriptedLookup(mapOf("example.com" to listOf(InetAddress.getByName("8.8.8.8"))))
        val transport =
            FakeTransport {
                PinnedHttpResponse(
                    status = 302,
                    headers = ResponseHeaders(listOf("Location" to "https://example.com/next")),
                    body = null,
                )
            }
        val client = client(lookup, transport, allowsPrivateAddresses = false)

        val response = get(client, "https://example.com/start")

        assertEquals(302, response.status)
        assertEquals(ActivityPubHttpClient.MAX_GET_REDIRECTS + 1, transport.calls.size)
    }

    @Test
    fun `POSTはリダイレクトを追わない`() {
        val pinned = InetAddress.getByName("1.1.1.1")
        val lookup = ScriptedLookup(mapOf("example.com" to listOf(pinned)))
        val transport =
            FakeTransport {
                PinnedHttpResponse(
                    status = 302,
                    headers = ResponseHeaders(listOf("Location" to "http://127.0.0.1/inbox")),
                    body = null,
                )
            }
        val client = client(lookup, transport, allowsPrivateAddresses = false)

        val response =
            runBlocking {
                client.post(
                    url = "https://example.com/inbox",
                    headers = mapOf("Host" to "example.com"),
                    body = byteArrayOf(1, 2),
                )
            }

        assertEquals(302, response.status)
        assertEquals(1, transport.calls.size)
        assertSame(pinned, transport.calls.single().addresses.single())
        assertEquals(true, transport.calls.single().body.contentEquals(byteArrayOf(1, 2)))
        assertEquals("example.com", transport.calls.single().headers["Host"])
    }

    @Test
    fun `POSTでも非公開アドレスには接続しない`() {
        val transport = FakeTransport { error("接続した") }
        val client = client(ScriptedLookup(mapOf()), transport, allowsPrivateAddresses = false)

        val failure =
            runCatching {
                runBlocking {
                    client.post(url = "http://10.1.2.3/inbox", headers = mapOf(), body = ByteArray(0))
                }
            }.exceptionOrNull()

        assertIs<OutboundAddressRejectedException>(failure)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `非公開アドレスを許す設定のときだけ接続する`() {
        val denied = FakeTransport { error("接続した") }
        val deniedClient = client(ScriptedLookup(mapOf()), denied, allowsPrivateAddresses = false)
        assertIs<OutboundAddressRejectedException>(
            runCatching { get(deniedClient, "http://127.0.0.1/inbox") }.exceptionOrNull(),
        )
        assertEquals(0, denied.calls.size)

        val allowed = okTransport()
        val allowedClient = client(ScriptedLookup(mapOf()), allowed, allowsPrivateAddresses = true)
        val response = get(allowedClient, "http://127.0.0.1/inbox")

        assertEquals(200, response.status)
        assertEquals(InetAddress.getByName("127.0.0.1"), allowed.calls.single().addresses.single())
    }

    @Test
    fun `httpとhttps以外には接続しない`() {
        val transport = FakeTransport { error("接続した") }
        val client = client(ScriptedLookup(mapOf()), transport, allowsPrivateAddresses = true)

        assertIs<OutboundAddressRejectedException>(
            runCatching { get(client, "ftp://127.0.0.1/a") }.exceptionOrNull(),
        )
        assertIs<OutboundAddressRejectedException>(
            runCatching { get(client, "file:///etc/passwd") }.exceptionOrNull(),
        )
        assertEquals(0, transport.calls.size)
    }

    private fun get(
        client: ActivityPubHttpClient,
        url: String,
    ): ActivityPubClientResponse =
        runBlocking {
            client.get(url = url, headers = mapOf(), maxBodyBytes = 64) { _, _ -> true }
        }

    private fun client(
        lookup: HostAddressLookup,
        transport: PinnedHttpTransport,
        allowsPrivateAddresses: Boolean,
    ): ActivityPubHttpClient =
        ActivityPubHttpClient(
            lookup = lookup,
            transport = transport,
            allowsPrivateAddresses = allowsPrivateAddresses,
        )

    private fun okTransport(): FakeTransport =
        FakeTransport {
            PinnedHttpResponse(
                status = 200,
                headers = ResponseHeaders(listOf()),
                body = Result.success("ok".encodeToByteArray()),
            )
        }

    private class ScriptedLookup(
        private val hosts: Map<String, List<InetAddress>>,
    ) : HostAddressLookup {
        var lookedUp: List<String> = listOf()

        override fun lookup(host: String): List<InetAddress> {
            lookedUp = lookedUp + host
            val scripted = hosts[host]
            if (scripted != null) return scripted
            if (isLiteral(host)) return InetAddress.getAllByName(host).toList()
            throw UnknownHostException(host)
        }

        private fun isLiteral(host: String): Boolean {
            if (host.startsWith("[")) return true
            val labels = host.split('.')
            return labels.size == 4 && labels.all { label -> label.toIntOrNull() in 0..255 }
        }
    }

    private class FakeTransport(
        private val handler: (PinnedHttpRequest) -> PinnedHttpResponse,
    ) : PinnedHttpTransport {
        var calls: List<PinnedHttpRequest> = listOf()

        override fun exchange(
            request: PinnedHttpRequest,
            readsBody: (status: Int, headers: ResponseHeaders) -> Boolean,
            maxBodyBytes: Int,
        ): PinnedHttpResponse {
            calls = calls + request
            val prepared = handler(request)
            if (!readsBody(prepared.status, prepared.headers)) {
                return PinnedHttpResponse(status = prepared.status, headers = prepared.headers, body = null)
            }
            return prepared
        }

        override fun close() = Unit
    }
}
