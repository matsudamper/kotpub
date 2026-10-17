package net.matsudamper.kotpub.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import net.matsudamper.kotpub.TestActorPrivateKey
import net.matsudamper.kotpub.TestRemoteActors
import net.matsudamper.kotpub.actor.ActorUrls
import net.matsudamper.kotpub.delivery.DeliveryResult
import net.matsudamper.kotpub.delivery.HttpActivityDelivery
import net.matsudamper.kotpub.httpsignature.HttpSignatureResult
import net.matsudamper.kotpub.httpsignature.HttpSignatureVerifier
import net.matsudamper.kotpub.httpsignature.SignedRequest

/**
 * Ktor で実際に HTTP を張って送る側。
 *
 * 署名文字列だけを突き合わせるテストでは、engine が後から足したり書き換えたりする
 * ヘッダの影響が見えない。`Host` が二重に付く、`Date` が engine のものに置き換わる、
 * といった壊れ方は実際に送ってみないと分からず、相手側では「署名が一致しない」と
 * しか見えないので、ここだけは本物のサーバーを立てて往復させる。
 */
class KtorActivityPubHttpClientTest {
    private val sender = ActorUrls(domain = "example.com", username = "admin")

    private val remoteActors =
        TestRemoteActors.of(
            keyId = sender.publicKeyId,
            owner = sender.actorId,
            publicKey = TestActorPrivateKey.value.publicKey,
        )

    /**
     * 受け取った側で署名を検証するサーバーを立て、[block] に宛先の URL を渡す。
     *
     * ポートは 0 で開いて実際に割り当てられたものを使う。固定すると
     * 並列に走るテストや開発機の他のプロセスとぶつかる。
     */
    private fun withInbox(block: suspend (url: String) -> Unit): HttpSignatureResult? {
        var received: HttpSignatureResult? = null

        val server =
            embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                routing {
                    post("/users/alice/inbox") {
                        val body = call.receive<ByteArray>()
                        received =
                            HttpSignatureVerifier(remoteActors).verify(
                                SignedRequest(
                                    method = call.request.httpMethod.value,
                                    requestTarget = call.request.uri,
                                    headers = KtorRequestHeaders(call.request.headers),
                                    body = body,
                                ),
                            )
                        call.respondText("", status = HttpStatusCode.Accepted)
                    }
                }
            }.start(wait = false)

        try {
            runBlocking {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                block("http://127.0.0.1:$port/users/alice/inbox")
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }

        return received
    }

    @Test
    fun `送った署名が相手側の検証を通る`() {
        var result: DeliveryResult? = null

        val verification =
            withInbox { url ->
                HttpActivityDelivery(TestActorPrivateKey.value, client = loopbackClient()).use { delivery ->
                    result =
                        delivery.deliver(inbox = url, sender = sender, body = """{"type":"Accept"}""".toByteArray())
                }
            }

        assertIs<DeliveryResult.Delivered>(result)
        val verified = assertIs<HttpSignatureResult.Verified>(verification)
        assertEquals(sender.actorId, verified.owner)
    }

    @Test
    fun `相手が受け取らなければ失敗として返る`() {
        var result: DeliveryResult? = null

        withInbox { url ->
            HttpActivityDelivery(TestActorPrivateKey.value, client = loopbackClient()).use { delivery ->
                // ルーティングに無いパスなので 404 が返る
                result =
                    delivery.deliver(
                        inbox = url.replace("/users/alice/inbox", "/users/bob/inbox"),
                        sender = sender,
                        body = """{"type":"Accept"}""".toByteArray(),
                    )
            }
        }

        assertIs<DeliveryResult.Failed>(result)
    }

    @Test
    fun `公開アドレスではない宛先には接続しない`() {
        KtorActivityPubHttpClient(null).use { client ->
            runBlocking {
                assertFailsWith<NonPublicAddressException> {
                    client.post(url = "http://127.0.0.1/", headers = mapOf(), body = byteArrayOf())
                }
                assertFailsWith<NonPublicAddressException> {
                    client.get(
                        url = "http://127.0.0.1/",
                        headers = mapOf(),
                        maxBodyBytes = 1,
                        readsBody = { _, _ -> false },
                    )
                }
            }
        }
    }

    @Test
    fun `リダイレクト先も名前解決で検査する`() {
        val resolvedHosts = mutableListOf<String>()
        val server =
            embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                routing {
                    get("/start") {
                        call.respondRedirect("http://192.168.0.1/secret")
                    }
                }
            }.start(wait = false)

        try {
            KtorActivityPubHttpClient(null) { hostname ->
                resolvedHosts.add(hostname)
                if (hostname == "127.0.0.1") {
                    listOf(hostname)
                } else {
                    throw NonPublicAddressException(hostname)
                }
            }.use { client ->
                runBlocking {
                    val port = server.engine.resolvedConnectors().first().port
                    assertFailsWith<NonPublicAddressException> {
                        client.get(
                            url = "http://127.0.0.1:$port/start",
                            headers = mapOf(),
                            maxBodyBytes = 1,
                            readsBody = { _, _ -> false },
                        )
                    }
                }
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }

        assertEquals(listOf("127.0.0.1", "192.168.0.1"), resolvedHosts)
    }
}

/**
 * 署名の往復はループバックのサーバーへ送る。公開アドレス検査はそこでは落ちるので、表記のまま繋ぐ。
 */
private fun loopbackClient(): KtorActivityPubHttpClient =
    KtorActivityPubHttpClient(null) { hostname -> listOf(hostname) }

/**
 * Ktor のヘッダを [RequestHeaders] として渡す。大文字小文字を区別しないのは Ktor 側と同じ
 */
private class KtorRequestHeaders(
    private val headers: Headers,
) : RequestHeaders {
    override fun getAll(name: String): List<String>? = headers.getAll(name)
}
