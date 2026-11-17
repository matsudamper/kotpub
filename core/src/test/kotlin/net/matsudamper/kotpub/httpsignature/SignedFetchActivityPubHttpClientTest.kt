package net.matsudamper.kotpub.httpsignature

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.TestActorPrivateKey
import net.matsudamper.kotpub.TestRemoteActors
import net.matsudamper.kotpub.actor.ActorUrls
import net.matsudamper.kotpub.actor.LocalActor
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse
import net.matsudamper.kotpub.http.RequestHeaders
import net.matsudamper.kotpub.http.ResponseBody

class SignedFetchActivityPubHttpClientTest {
    private val actor = LocalActor(
        urls = ActorUrls(domain = "example.com", username = "admin"),
        privateKey = TestActorPrivateKey.value,
    )
    private val remoteActors = TestRemoteActors.of(
        keyId = actor.urls.publicKeyId,
        owner = actor.urls.actorId,
        publicKey = TestActorPrivateKey.value.publicKey,
    )

    @Test
    fun `GETの署名が相手側の検証を通る`() {
        val recording = RecordingClient()
        val client = SignedFetchActivityPubHttpClient(recording, { actor }, HttpSignatureSigner())

        runBlocking { client.get("https://remote.example/users/alice/statuses/1?page=1", mapOf("Accept" to "application/activity+json"), 1024) { _, _ -> false } }

        val sent = URI(recording.url)
        val verification = runBlocking {
            HttpSignatureVerifier(remoteActors).verify(
                SignedRequest(
                    method = "GET",
                    requestTarget = "${sent.rawPath}?${sent.rawQuery}",
                    headers = RequestHeaders.ofSingleValues(recording.headers),
                    body = ByteArray(0),
                ),
            )
        }
        assertEquals(actor.urls.actorId, assertIs<HttpSignatureResult.Verified>(verification).owner)
        assertEquals("application/activity+json", recording.headers["Accept"])
    }

    @Test
    fun `署名に使うアクターが居なければ署名しない`() {
        val recording = RecordingClient()
        val client = SignedFetchActivityPubHttpClient(recording, { null }, HttpSignatureSigner())

        runBlocking { client.get("https://remote.example/users/alice", mapOf("Accept" to "application/activity+json"), 1024) { _, _ -> false } }

        assertEquals(mapOf("Accept" to "application/activity+json"), recording.headers)
        assertFalse("Signature" in recording.headers)
    }

    private class RecordingClient : ActivityPubHttpClient {
        var url: String = ""
        var headers: Map<String, String> = mapOf()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBodyBytes: Int,
            readsBody: (status: Int, finalUrl: String) -> Boolean,
        ): ActivityPubClientResponse {
            this.url = url
            this.headers = headers
            return ActivityPubClientResponse(status = 200, finalUrl = url, body = ResponseBody.NotRead)
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
        ): ActivityPubPostResponse = error("POST は使わない")

        override fun close() = Unit
    }
}
