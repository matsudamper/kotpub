package net.matsudamper.kotpub.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.matsudamper.kotpub.crypto.RsaKeys
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.http.ResponseBody

class HttpRemoteActorsTest {
    @Test
    fun profileUrlReturnsDocumentActorId() = runBlocking {
        assertEquals("https://remote.example/users/alice", assertIs<RemoteActorLookup.Found>(findActor("\"https://remote.example/users/alice\"")).actor.actorId)
    }

    @Test
    fun rejectsDocumentIdOnAnotherHost() = runBlocking {
        assertInvalidDocument(findActor("\"https://other.example/users/alice\""))
    }

    @Test
    fun rejectsMissingOrNonHttpsDocumentId() = runBlocking {
        assertInvalidDocument(findActor("null"))
        assertInvalidDocument(findActor("\"http://remote.example/users/alice\""))
        assertInvalidDocument(findActor("\"invalid\""))
    }

    @Test
    fun findActorByUrlRefetchesFromOtherHostRedirectTarget() = runBlocking {
        val client = RedirectingClient()

        val actor = HttpRemoteActors(client, null).use { it.findActorByUrl(RedirectingClient.PROFILE_URL) }

        assertEquals(RedirectingClient.ACTOR_ID, assertIs<RemoteActorLookup.Found>(actor).actor.actorId)
        assertEquals(listOf(RedirectingClient.PROFILE_URL, RedirectingClient.ACTOR_ID), client.requestedUrls)
    }

    @Test
    fun findActorByUrlReturnsRedirectTargetAgainWithinRefreshInterval() = runBlocking {
        val actors = HttpRemoteActors(RedirectingClient(), null)

        actors.use {
            assertEquals(RedirectingClient.ACTOR_ID, assertIs<RemoteActorLookup.Found>(it.findActorByUrl(RedirectingClient.PROFILE_URL)).actor.actorId)
            assertEquals(RedirectingClient.ACTOR_ID, assertIs<RemoteActorLookup.Found>(it.findActorByUrl(RedirectingClient.PROFILE_URL)).actor.actorId)
        }
    }

    @Test
    fun findActorDoesNotFollowOtherHostRedirect() = runBlocking {
        val lookup = HttpRemoteActors(RedirectingClient(), null).use { it.findActor(RedirectingClient.PROFILE_URL) }
        assertIs<RemoteFetchFailure.RedirectedToDisallowedUrl>(assertIs<RemoteActorLookup.Unavailable>(lookup).failure)
    }

    private class RedirectingClient : ActivityPubHttpClient {
        val requestedUrls: MutableList<String> = mutableListOf()

        /**
         * 読める鍵でないと覚えてもらえず、取得し直しの間隔の中で 2 回目が引けない
         */
        private val actorDocument: ByteArray =
            buildJsonObject {
                put("id", ACTOR_ID)
                put("inbox", "https://remote.example/inbox")
                putJsonObject("publicKey") {
                    put("owner", ACTOR_ID)
                    put("publicKeyPem", RsaKeys.encodeToPem(RsaKeys.generateKeyPair().public))
                }
            }.toString().encodeToByteArray()

        override suspend fun get(url: String, headers: Map<String, String>, maxBodyBytes: Int, readsBody: (Int, String) -> Boolean): ActivityPubClientResponse {
            requestedUrls += url
            if (url == PROFILE_URL) return ActivityPubClientResponse(200, ACTOR_ID, ResponseBody.NotRead)
            return ActivityPubClientResponse(
                200,
                url,
                ResponseBody.Read(actorDocument),
            )
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): ActivityPubPostResponse = error("POST は不要")

        override fun close() = Unit

        companion object {
            const val PROFILE_URL = "https://viewer.example/@alice@remote.example"
            const val ACTOR_ID = "https://remote.example/users/alice"
        }
    }

    private fun assertInvalidDocument(lookup: RemoteActorLookup) {
        assertIs<RemoteFetchFailure.InvalidDocument>(assertIs<RemoteActorLookup.Unavailable>(lookup).failure)
    }

    private suspend fun findActor(documentId: String): RemoteActorLookup {
        val client = object : ActivityPubHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, maxBodyBytes: Int, readsBody: (Int, String) -> Boolean): ActivityPubClientResponse =
                ActivityPubClientResponse(
                    200,
                    url,
                    ResponseBody.Read(
                        """{"id":$documentId,"inbox":"https://remote.example/inbox","publicKey":{"publicKeyPem":"key"}}""".encodeToByteArray(),
                    ),
                )

            override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): ActivityPubPostResponse = error("POST は不要")

            override fun close() = Unit
        }
        return HttpRemoteActors(client, null).use { it.findActor("https://remote.example/@alice") }
    }
}
