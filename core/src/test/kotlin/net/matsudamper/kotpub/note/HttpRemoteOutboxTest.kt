package net.matsudamper.kotpub.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse

class HttpRemoteOutboxTest {
    private val actorId = "https://remote.example/users/alice"
    private val outboxUrl = "$actorId/outbox"
    private val requests = mutableListOf<String>()
    private val documents = mutableMapOf<String, String>()
    private val client = object : ActivityPubHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>, maxBodyBytes: Int, readsBody: (Int, String) -> Boolean): ActivityPubClientResponse {
            requests.add(url)
            val document = documents[url]
            val status = if (document == null) 404 else 200
            val body = if (document != null && readsBody(status, url)) Result.success(document.encodeToByteArray()) else null
            return ActivityPubClientResponse(status, url, body)
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): ActivityPubPostResponse = error("POST は不要")

        override fun close() = Unit
    }
    private val outbox = HttpRemoteOutbox(client)

    @Test
    fun followsFirstPageAndOnlyReturnsPublicNotesFromRequestedActor() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","first":"$outboxUrl?page=1"}"""
        documents["$outboxUrl?page=1"] = """{"orderedItems":[
            {"type":"Create","actor":"$actorId","object":${note("first", actorId, true)}},
            ${note("private", actorId, false)},
            ${note("other", "https://other.example/users/bob", true)},
            {"type":"Announce","object":${note("boost", actorId, true)}},
            ${note("first", actorId, true)}
        ]}"""
        val notes = outbox.recent(actorId, 20)
        assertEquals(listOf("$actorId/first"), notes.map { it.id })
        assertEquals(listOf(actorId, outboxUrl, "$outboxUrl?page=1"), requests)
    }

    @Test
    fun doesNotFollowOutboxOnAnotherHost() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"https://other.example/outbox"}"""
        assertFailsWith<IllegalStateException> { outbox.recent(actorId, 20) }
        assertEquals(listOf(actorId), requests)
    }

    @Test
    fun stopsAtRequestedLimitWithoutLoadingNextPage() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"orderedItems":[${note("first", actorId, true)}],"next":"$outboxUrl?page=2"}"""
        assertEquals(1, outbox.recent(actorId, 1).size)
        assertEquals(listOf(actorId, outboxUrl), requests)
    }

    private fun note(id: String, author: String, public: Boolean): String =
        """{"type":"Note","id":"$actorId/$id","attributedTo":"$author","content":"<p>投稿</p>","published":"2026-11-01T00:00:00Z","to":["${if (public) "https://www.w3.org/ns/activitystreams#Public" else actorId}"]}"""
}
