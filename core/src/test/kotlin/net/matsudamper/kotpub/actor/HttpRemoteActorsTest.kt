package net.matsudamper.kotpub.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse

class HttpRemoteActorsTest {
    @Test
    fun profileUrlReturnsDocumentActorId() = runBlocking {
        assertEquals("https://remote.example/users/alice", findActor("\"https://remote.example/users/alice\"")?.actorId)
    }

    @Test
    fun rejectsDocumentIdOnAnotherHost() = runBlocking {
        assertNull(findActor("\"https://other.example/users/alice\""))
    }

    @Test
    fun rejectsMissingOrNonHttpsDocumentId() = runBlocking {
        assertNull(findActor("null"))
        assertNull(findActor("\"http://remote.example/users/alice\""))
        assertNull(findActor("\"invalid\""))
    }

    private suspend fun findActor(documentId: String): RemoteActor? {
        val client = object : ActivityPubHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, maxBodyBytes: Int, readsBody: (Int, String) -> Boolean): ActivityPubClientResponse =
                ActivityPubClientResponse(
                    200,
                    url,
                    Result.success(
                        """{"id":$documentId,"inbox":"https://remote.example/inbox","publicKey":{"publicKeyPem":"key"}}""".encodeToByteArray(),
                    ),
                )

            override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): ActivityPubPostResponse = error("POST は不要")

            override fun close() = Unit
        }
        return HttpRemoteActors(client, null).use { it.findActor("https://remote.example/@alice") }
    }
}
