package net.matsudamper.kotpub.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.emoji.CustomEmoji
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
    fun featuredKeepsTheOrderTheActorPinned() = runBlocking {
        val featuredUrl = "$actorId/collections/featured"
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl","featured":"$featuredUrl"}"""
        documents[featuredUrl] = """{"id":"$featuredUrl","orderedItems":[
            ${note("older", actorId, true, "2026-01-01T00:00:00Z")},
            ${note("private", actorId, false)},
            ${note("newer", actorId, true, "2026-02-01T00:00:00Z")}
        ]}"""
        assertEquals(listOf("$actorId/older", "$actorId/newer"), outbox.featured(actorId, 20).map { it.id })
        assertEquals(listOf(actorId, featuredUrl), requests)
    }

    @Test
    fun featuredIsEmptyWhenActorHasNoFeaturedCollection() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        assertEquals(listOf(), outbox.featured(actorId, 20))
        assertEquals(listOf(actorId), requests)
    }

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

    @Test
    fun doesNotCountCollectionEntryAsPage() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","first":"$outboxUrl?page=1"}"""
        documents["$outboxUrl?page=1"] = """{"id":"$outboxUrl?page=1","orderedItems":[],"next":"$outboxUrl?page=2"}"""
        documents["$outboxUrl?page=2"] = """{"id":"$outboxUrl?page=2","orderedItems":[],"next":"$outboxUrl?page=3"}"""
        documents["$outboxUrl?page=3"] = """{"id":"$outboxUrl?page=3","orderedItems":[${note("third", actorId, true)}],"next":"$outboxUrl?page=4"}"""
        assertEquals(listOf("$actorId/third"), outbox.recent(actorId, 20).map { it.id })
        assertEquals(listOf(actorId, outboxUrl, "$outboxUrl?page=1", "$outboxUrl?page=2", "$outboxUrl?page=3"), requests)
    }

    @Test
    fun countsOnlyUniqueNotesTowardLimit() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[${note("a", actorId, true)}],"next":"$outboxUrl?page=2"}"""
        documents["$outboxUrl?page=2"] = """{"id":"$outboxUrl?page=2","orderedItems":[${note("a", actorId, true)},${note("b", actorId, true)}]}"""
        assertEquals(setOf("$actorId/a", "$actorId/b"), outbox.recent(actorId, 2).map { it.id }.toSet())
    }

    @Test
    fun scansItemsBeyondNoteLimitInSamePage() = runBlocking {
        val boosts = (1..20).joinToString(",") { """{"type":"Announce","object":${note("boost$it", actorId, true)}}""" }
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[$boosts,${note("own", actorId, true)}]}"""
        assertEquals(listOf("$actorId/own"), outbox.recent(actorId, 1).map { it.id })
    }

    @Test
    fun returnsAttachmentsAndContentWarning() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"orderedItems":[{"type":"Note","id":"$actorId/media","attributedTo":"$actorId","content":"<p>画像</p>",
            "published":"2026-11-01T00:00:00Z","to":["https://www.w3.org/ns/activitystreams#Public"],"summary":"注意","sensitive":true,
            "attachment":[{"type":"Document","mediaType":"image/png","url":"https://remote.example/a.png","name":"説明"}]}]}"""
        val note = outbox.recent(actorId, 20).single()
        assertEquals("注意", note.summary)
        assertEquals(true, note.sensitive)
        assertEquals(listOf(ReceivedNoteAttachment(url = "https://remote.example/a.png", mediaType = "image/png", name = "説明")), note.attachments)
    }

    @Test
    fun returnsCustomEmojis() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"orderedItems":[{"type":"Note","id":"$actorId/emoji","attributedTo":"$actorId","content":"<p>:blob:</p>",
            "published":"2026-11-01T00:00:00Z","to":["https://www.w3.org/ns/activitystreams#Public"],
            "tag":[{"type":"Emoji","name":":blob:","icon":{"type":"Image","url":"https://remote.example/blob.png"}}]}]}"""
        val note = outbox.recent(actorId, 20).single()
        assertEquals(listOf(CustomEmoji(name = ":blob:", imageUrl = "https://remote.example/blob.png")), note.emojis)
    }

    private fun note(id: String, author: String, public: Boolean): String = note(id, author, public, "2026-11-01T00:00:00Z")

    private fun note(id: String, author: String, public: Boolean, published: String): String =
        """{"type":"Note","id":"$actorId/$id","attributedTo":"$author","content":"<p>投稿</p>","published":"$published","to":["${if (public) "https://www.w3.org/ns/activitystreams#Public" else actorId}"]}"""
}
