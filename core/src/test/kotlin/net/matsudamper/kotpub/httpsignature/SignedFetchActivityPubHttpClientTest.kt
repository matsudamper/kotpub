package net.matsudamper.kotpub.httpsignature

import java.io.IOException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.TestActorPrivateKey
import net.matsudamper.kotpub.TestRemoteActors
import net.matsudamper.kotpub.actor.InstanceActor
import net.matsudamper.kotpub.actor.InstanceActorUrls
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubPostResponse
import net.matsudamper.kotpub.http.RequestHeaders
import net.matsudamper.kotpub.http.ResponseBody
import net.matsudamper.kotpub.http.SingleHopActivityPubHttpClient
import net.matsudamper.kotpub.http.SingleHopGetResponse

class SignedFetchActivityPubHttpClientTest {
    private val actor = InstanceActor(
        urls = InstanceActorUrls(domain = "example.com"),
        privateKey = TestActorPrivateKey.value,
    )
    private val remoteActors = TestRemoteActors.of(
        keyId = actor.urls.publicKeyId,
        owner = actor.urls.actorId,
        publicKey = TestActorPrivateKey.value.publicKey,
    )

    @Test
    fun `GETの署名が相手側の検証を通る`() {
        val recording = RecordingClient(redirects = mapOf())
        val client = SignedFetchActivityPubHttpClient(recording, actor, HttpSignatureSigner())

        runBlocking { client.get("https://remote.example/users/alice/statuses/1?page=1", mapOf("Accept" to "application/activity+json"), 1024) { _, _ -> false } }

        val sent = recording.requests.single()
        assertEquals(actor.urls.actorId, verify(sent))
        assertEquals("application/activity+json", sent.headers["Accept"])
    }

    @Test
    fun `リダイレクトの移り先ごとに署名し直す`() {
        val recording = RecordingClient(
            redirects = mapOf(
                "https://remote.example/@alice/1" to "/users/alice/statuses/1",
                "https://remote.example/users/alice/statuses/1" to "https://other.example/notes/1",
            ),
        )
        val client = SignedFetchActivityPubHttpClient(recording, actor, HttpSignatureSigner())

        val response = runBlocking { client.get("https://remote.example/@alice/1", mapOf("Accept" to "application/activity+json"), 1024) { _, _ -> false } }

        assertEquals(
            listOf("https://remote.example/@alice/1", "https://remote.example/users/alice/statuses/1", "https://other.example/notes/1"),
            recording.requests.map { it.url },
        )
        recording.requests.forEach { request -> assertEquals(actor.urls.actorId, verify(request), request.url) }
        assertEquals("other.example", recording.requests.last().headers["Host"])
        assertEquals("https://other.example/notes/1", response.finalUrl)
    }

    @Test
    fun `リダイレクトが続くと諦める`() {
        val recording = RecordingClient(redirects = mapOf("https://remote.example/loop" to "/loop"))
        val client = SignedFetchActivityPubHttpClient(recording, actor, HttpSignatureSigner())

        assertFailsWith<IOException> {
            runBlocking { client.get("https://remote.example/loop", mapOf(), 1024) { _, _ -> false } }
        }
    }

    @Test
    fun `渡したヘッダの同名のものは大文字小文字を問わず署名したものに置き換える`() {
        val recording = RecordingClient(redirects = mapOf())
        val client = SignedFetchActivityPubHttpClient(recording, actor, HttpSignatureSigner())

        runBlocking { client.get("https://remote.example/users/alice", mapOf("date" to "old", "host" to "other.example"), 1024) { _, _ -> false } }

        val sent = recording.requests.single()
        assertEquals(setOf("Host", "Date", "Signature"), sent.headers.keys)
        assertEquals("remote.example", sent.headers["Host"])
    }

    private fun verify(request: RecordedRequest): String? {
        val sent = URI(request.url)
        val requestTarget = if (sent.rawQuery == null) sent.rawPath else "${sent.rawPath}?${sent.rawQuery}"
        val verification = runBlocking {
            HttpSignatureVerifier(remoteActors).verify(
                SignedRequest(
                    method = "GET",
                    requestTarget = requestTarget,
                    headers = RequestHeaders.ofSingleValues(request.headers),
                    body = ByteArray(0),
                ),
            )
        }
        return assertIs<HttpSignatureResult.Verified>(verification).owner
    }

    private class RecordedRequest(
        val url: String,
        val headers: Map<String, String>,
    )

    /**
     * @param redirects URL ごとの `Location`。載っていない URL には 200 を返す
     */
    private class RecordingClient(
        private val redirects: Map<String, String>,
    ) : SingleHopActivityPubHttpClient {
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun getSingleHop(
            url: String,
            headers: Map<String, String>,
            maxBodyBytes: Int,
            readsBody: (status: Int, finalUrl: String) -> Boolean,
        ): SingleHopGetResponse {
            requests += RecordedRequest(url, headers)
            val location = redirects[url]
            if (location != null) return SingleHopGetResponse.Redirect(location)
            return SingleHopGetResponse.Final(ActivityPubClientResponse(status = 200, finalUrl = url, body = ResponseBody.NotRead))
        }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBodyBytes: Int,
            readsBody: (status: Int, finalUrl: String) -> Boolean,
        ): ActivityPubClientResponse = error("リダイレクトを自分で追う get は使わない")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
        ): ActivityPubPostResponse = error("POST は使わない")

        override fun close() = Unit
    }
}
