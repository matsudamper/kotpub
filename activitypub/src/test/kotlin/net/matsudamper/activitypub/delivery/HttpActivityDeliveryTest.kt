package net.matsudamper.activitypub.delivery

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.matsudamper.activitypub.TestActorKey
import net.matsudamper.activitypub.TestRemoteActors
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.http.ActivityPubClientResponse
import net.matsudamper.activitypub.http.ActivityPubHttpClient
import net.matsudamper.activitypub.http.ActivityPubPostResponse
import net.matsudamper.activitypub.http.HttpStatusCodes
import net.matsudamper.activitypub.http.RequestHeaders
import net.matsudamper.activitypub.httpsignature.HttpSignatureResult
import net.matsudamper.activitypub.httpsignature.HttpSignatureVerifier
import net.matsudamper.activitypub.httpsignature.SignedRequest

/**
 * 署名を付けて POST する部分。通信そのものは差し替えて、渡したものと応答の扱いを見る
 */
class HttpActivityDeliveryTest {
    private val sender = ActorUrls(domain = "example.com", username = "admin")

    private val remoteActors =
        TestRemoteActors.of(
            keyId = sender.publicKeyId,
            owner = sender.actorId,
            publicKey = TestActorKey.value.publicKey,
        )

    @Test
    fun `渡した署名が相手側の検証を通る`() {
        val client = RecordingClient(status = HttpStatusCodes.ACCEPTED)
        val body = """{"type":"Accept"}""".toByteArray()

        val result =
            runBlocking {
                HttpActivityDelivery(TestActorKey.value, client = client).use { delivery ->
                    delivery.deliver(inbox = "https://remote.example/users/alice/inbox?a=1", sender = sender, body = body)
                }
            }
        assertIs<DeliveryResult.Delivered>(result)

        val sent = URI(client.url)
        val verification =
            runBlocking {
                HttpSignatureVerifier(remoteActors).verify(
                    SignedRequest(
                        method = "POST",
                        requestTarget = "${sent.rawPath}?${sent.rawQuery}",
                        headers = RequestHeaders.ofSingleValues(client.headers),
                        body = client.body,
                    ),
                )
            }
        val verified = assertIs<HttpSignatureResult.Verified>(verification)
        assertEquals(sender.actorId, verified.owner)
        assertEquals("remote.example", client.headers["Host"])
        assertEquals("application/activity+json", client.headers["Content-Type"])
    }

    @Test
    fun `既定でないポートはHostに付ける`() {
        val client = RecordingClient(status = HttpStatusCodes.ACCEPTED)

        runBlocking {
            HttpActivityDelivery(TestActorKey.value, client = client).use { delivery ->
                delivery.deliver(inbox = "https://remote.example:8443/inbox", sender = sender, body = ByteArray(0))
            }
        }

        assertEquals("remote.example:8443", client.headers["Host"])
    }

    @Test
    fun `非ASCIIのパスは送るときと同じ符号化で署名する`() {
        val client = RecordingClient(status = HttpStatusCodes.ACCEPTED)
        val body = """{"type":"Accept"}""".toByteArray()

        runBlocking {
            HttpActivityDelivery(TestActorKey.value, client = client).use { delivery ->
                delivery.deliver(inbox = "https://remote.example/users/あ/inbox", sender = sender, body = body)
            }
        }

        val verification =
            runBlocking {
                HttpSignatureVerifier(remoteActors).verify(
                    SignedRequest(
                        method = "POST",
                        requestTarget = "/users/%E3%81%82/inbox",
                        headers = RequestHeaders.ofSingleValues(client.headers),
                        body = client.body,
                    ),
                )
            }
        assertIs<HttpSignatureResult.Verified>(verification)
    }

    @Test
    fun `送信の途中で止められたら失敗として返さない`() {
        val started = CompletableDeferred<Unit>()
        val client = object : FakeClient() {
            override suspend fun post(
                url: String,
                headers: Map<String, String>,
                body: ByteArray,
            ): ActivityPubPostResponse {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        var result: DeliveryResult? = null

        runBlocking {
            HttpActivityDelivery(TestActorKey.value, client = client).use { delivery ->
                val job = launch {
                    result = delivery.deliver(
                        inbox = "https://example.com/users/alice/inbox",
                        sender = sender,
                        body = """{"type":"Create"}""".toByteArray(),
                    )
                }
                started.await()
                job.cancelAndJoin()
            }
        }

        assertNull(result)
    }

    @Test
    fun `届かなければ送り直す失敗として返る`() {
        val client = object : FakeClient() {
            override suspend fun post(
                url: String,
                headers: Map<String, String>,
                body: ByteArray,
            ): ActivityPubPostResponse = throw java.io.IOException("接続できない")
        }

        val result =
            runBlocking {
                HttpActivityDelivery(TestActorKey.value, client = client).use { delivery ->
                    delivery.deliver(inbox = "https://example.com/users/alice/inbox", sender = sender, body = ByteArray(0))
                }
            }

        assertEquals(true, assertIs<DeliveryResult.Failed>(result).retryable)
    }

    @Test
    fun `URL として読めない宛先は送らずに失敗として返る`() {
        val client = RecordingClient(status = HttpStatusCodes.ACCEPTED)
        val result =
            runBlocking {
                HttpActivityDelivery(TestActorKey.value, client = client).use { delivery ->
                    delivery.deliver(inbox = "not a url", sender = sender, body = ByteArray(0))
                }
            }

        assertIs<DeliveryResult.Failed>(result)
        assertNull(client.sentUrl)
    }

    @Test
    fun `相手が受け取らないと決めた応答は送り直さない`() {
        assertEquals(false, deliverTo(400).retryable)
        assertEquals(false, deliverTo(403).retryable)
        assertEquals(false, deliverTo(404).retryable)
        assertEquals(false, deliverTo(410).retryable)
        assertEquals(false, deliverTo(501).retryable)
    }

    @Test
    fun `詰まっているだけの応答は送り直す`() {
        // 鍵の入れ替え中・詰まっている・落ちている。後なら通る
        assertEquals(true, deliverTo(401).retryable)
        assertEquals(true, deliverTo(408).retryable)
        assertEquals(true, deliverTo(429).retryable)
        assertEquals(true, deliverTo(500).retryable)
        assertEquals(true, deliverTo(503).retryable)
    }

    private fun deliverTo(status: Int): DeliveryResult.Failed {
        val result =
            runBlocking {
                HttpActivityDelivery(TestActorKey.value, client = RecordingClient(status)).use { delivery ->
                    delivery.deliver(
                        inbox = "https://example.com/users/alice/inbox",
                        sender = sender,
                        body = """{"type":"Create"}""".toByteArray(),
                    )
                }
            }

        return assertIs<DeliveryResult.Failed>(result)
    }

    private abstract class FakeClient : ActivityPubHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBodyBytes: Int,
            readsBody: (status: Int, finalUrl: String) -> Boolean,
        ): ActivityPubClientResponse = error("GET は使わない")

        override fun close() = Unit
    }

    private class RecordingClient(
        private val status: Int,
    ) : FakeClient() {
        var sentUrl: String? = null
        var headers: Map<String, String> = mapOf()
        var body: ByteArray = ByteArray(0)
        val url: String get() = requireNotNull(sentUrl)

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
        ): ActivityPubPostResponse {
            sentUrl = url
            this.headers = headers
            this.body = body
            return ActivityPubPostResponse(status = status)
        }
    }
}
