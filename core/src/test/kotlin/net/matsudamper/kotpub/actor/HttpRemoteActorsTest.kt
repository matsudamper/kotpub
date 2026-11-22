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
import net.matsudamper.kotpub.httpsignature.PublicKeyLookup

class HttpRemoteActorsTest {
    @Test
    fun profileUrlReturnsDocumentActorId() {
        runBlocking {
            assertEquals("https://remote.example/users/alice", assertIs<RemoteActorLookup.Found>(findActor("\"https://remote.example/users/alice\"")).actor.actorId)
        }
    }

    @Test
    fun rejectsDocumentIdOnAnotherHost() {
        runBlocking {
            assertInvalidDocument(findActor("\"https://other.example/users/alice\""))
        }
    }

    @Test
    fun rejectsMissingOrNonHttpsDocumentId() {
        runBlocking {
            assertInvalidDocument(findActor("null"))
            assertInvalidDocument(findActor("\"http://remote.example/users/alice\""))
            assertInvalidDocument(findActor("\"invalid\""))
        }
    }

    @Test
    fun findActorByUrlRefetchesFromOtherHostRedirectTarget() {
        runBlocking {
            val client = RedirectingClient()

            val actor = HttpRemoteActors(client, null).use { it.findActorByUrl(RedirectingClient.PROFILE_URL) }

            assertEquals(RedirectingClient.ACTOR_ID, assertIs<RemoteActorLookup.Found>(actor).actor.actorId)
            assertEquals(listOf(RedirectingClient.PROFILE_URL, RedirectingClient.ACTOR_ID), client.requestedUrls)
        }
    }

    @Test
    fun findActorByUrlReturnsRedirectTargetAgainWithinRefreshInterval() {
        runBlocking {
            val actors = HttpRemoteActors(RedirectingClient(), null)

            actors.use {
                assertEquals(RedirectingClient.ACTOR_ID, assertIs<RemoteActorLookup.Found>(it.findActorByUrl(RedirectingClient.PROFILE_URL)).actor.actorId)
                assertEquals(RedirectingClient.ACTOR_ID, assertIs<RemoteActorLookup.Found>(it.findActorByUrl(RedirectingClient.PROFILE_URL)).actor.actorId)
            }
        }
    }

    @Test
    fun findActorDoesNotFollowOtherHostRedirect() {
        runBlocking {
            val lookup = HttpRemoteActors(RedirectingClient(), null).use { it.findActor(RedirectingClient.PROFILE_URL) }
            assertIs<RemoteFetchFailure.RedirectedToDisallowedUrl>(assertIs<RemoteActorLookup.Unavailable>(lookup).failure)
        }
    }

    @Test
    fun findActorByUrlReusesProfileUrlDocumentWithinRefreshInterval() {
        runBlocking {
            HttpRemoteActors(KeyDocumentClient(ownerListedKeyId = UPLOADED_KEY_ID), null).use {
                assertEquals(VICTIM_ID, assertIs<RemoteActorLookup.Found>(it.findActorByUrl(VICTIM_PROFILE_URL)).actor.actorId)
                assertEquals(VICTIM_ID, assertIs<RemoteActorLookup.Found>(it.findActorByUrl(VICTIM_PROFILE_URL)).actor.actorId)
            }
        }
    }

    @Test
    fun findFreshActorDoesNotReturnRememberedDocumentWithinRefreshInterval() {
        runBlocking {
            HttpRemoteActors(KeyDocumentClient(ownerListedKeyId = UPLOADED_KEY_ID), null).use {
                assertIs<RemoteActorLookup.Found>(it.findActor(VICTIM_ID))

                assertIs<RemoteActorLookup.Found>(it.findActor(VICTIM_ID))
                assertIs<RemoteFetchFailure.Throttled>(assertIs<RemoteActorLookup.Unavailable>(it.findFreshActor(VICTIM_ID)).failure)
            }
        }
    }

    @Test
    fun findFreshActorFetchesWhenNotFetchedRecently() {
        runBlocking {
            HttpRemoteActors(KeyDocumentClient(ownerListedKeyId = UPLOADED_KEY_ID), null).use {
                assertEquals(VICTIM_ID, assertIs<RemoteActorLookup.Found>(it.findFreshActor(VICTIM_ID)).actor.actorId)
            }
        }
    }

    @Test
    fun rejectsKeyDocumentClaimingOwnerThatDoesNotListKeyId() {
        runBlocking {
            val lookup = HttpRemoteActors(KeyDocumentClient(ownerListedKeyId = "$VICTIM_ID#main-key"), null).use { it.find(UPLOADED_KEY_ID) }
            assertIs<RemoteFetchFailure.InvalidDocument>(assertIs<PublicKeyLookup.Unavailable>(lookup).failure)
        }
    }

    @Test
    fun acceptsKeyDocumentWhoseOwnerListsKeyId() {
        runBlocking {
            val lookup = HttpRemoteActors(KeyDocumentClient(ownerListedKeyId = UPLOADED_KEY_ID), null).use { it.find(UPLOADED_KEY_ID) }
            assertEquals(VICTIM_ID, assertIs<PublicKeyLookup.Found>(lookup).key.owner)
        }
    }

    @Test
    fun rejectsKeyDocumentWhoseIdDiffersFromKeyId() {
        runBlocking {
            val lookup = HttpRemoteActors(KeyDocumentClient(ownerListedKeyId = UPLOADED_KEY_ID), null).use { it.find("https://remote.example/media/other.json") }
            assertIs<RemoteFetchFailure.InvalidDocument>(assertIs<PublicKeyLookup.Unavailable>(lookup).failure)
        }
    }

    /**
     * [UPLOADED_KEY_ID] には持ち主を [VICTIM_ID] と名乗る鍵の文書を、[VICTIM_ID] と [VICTIM_PROFILE_URL] には [ownerListedKeyId] を載せたアクター文書を返す
     */
    private class KeyDocumentClient(
        private val ownerListedKeyId: String,
    ) : ActivityPubHttpClient {
        private val publicKeyPem = RsaKeys.encodeToPem(RsaKeys.generateKeyPair().public)

        override suspend fun get(url: String, headers: Map<String, String>, maxBodyBytes: Int, readsBody: (Int, String) -> Boolean): ActivityPubClientResponse {
            val document =
                if (url == VICTIM_ID || url == VICTIM_PROFILE_URL) {
                    buildJsonObject {
                        put("id", VICTIM_ID)
                        put("inbox", "https://remote.example/inbox")
                        putJsonObject("publicKey") {
                            put("id", ownerListedKeyId)
                            put("owner", VICTIM_ID)
                            put("publicKeyPem", publicKeyPem)
                        }
                    }
                } else {
                    buildJsonObject {
                        put("id", UPLOADED_KEY_ID)
                        putJsonObject("publicKey") {
                            put("id", UPLOADED_KEY_ID)
                            put("owner", VICTIM_ID)
                            put("publicKeyPem", publicKeyPem)
                        }
                    }
                }
            return ActivityPubClientResponse(200, url, ResponseBody.Read(document.toString().encodeToByteArray()))
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): ActivityPubPostResponse = error("POST は不要")

        override fun close() = Unit
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

    private companion object {
        const val VICTIM_ID = "https://remote.example/users/victim"
        const val VICTIM_PROFILE_URL = "https://remote.example/@victim"
        const val UPLOADED_KEY_ID = "https://remote.example/media/key.json"
    }
}
