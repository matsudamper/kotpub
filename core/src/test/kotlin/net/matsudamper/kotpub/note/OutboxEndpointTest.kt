package net.matsudamper.kotpub.note

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.matsudamper.kotpub.FakeStoredActorNames
import net.matsudamper.kotpub.TestLocalActor
import net.matsudamper.kotpub.activity.ActivityStreamsIri
import net.matsudamper.kotpub.activity.BoostAudience
import net.matsudamper.kotpub.actor.ActorDirectory
import net.matsudamper.kotpub.collection.COLLECTION_PAGE_SIZE
import net.matsudamper.kotpub.collection.OrderedCollection
import net.matsudamper.kotpub.collection.OrderedCollectionPage
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.json.AppJson

class OutboxEndpointTest {
    private val directory = ActorDirectory(
        domain = TestLocalActor.DOMAIN,
        stored = FakeStoredActorNames(listOf(TestLocalActor.USERNAME)),
    )

    @Test
    fun `投稿とブーストを新しい順に混ぜて並べる`() = runBlocking {
        val endpoint = OutboxEndpoint(
            directory = directory,
            notes = ListNoteStore(listOf(note("new", "2026-11-03T00:00:00Z"), note("old", "2026-11-01T00:00:00Z"))),
            boosts = ListBoostStore(listOf(boost("boost", "2026-11-02T00:00:00Z", BoostAudience.UNLISTED))),
            webPages = null,
        )

        val page = page(endpoint, cursor = "")

        assertEquals(3L, page.totalItems)
        assertEquals(listOf("Create", "Announce", "Create"), page.orderedItems.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        val announce = page.orderedItems[1].jsonObject
        assertEquals("$ACTOR/announces/boost", announce.getValue("id").jsonPrimitive.content)
        assertEquals(TestLocalActor.urls.actorId, announce.getValue("actor").jsonPrimitive.content)
        assertEquals(REMOTE_NOTE, announce.getValue("object").jsonPrimitive.content)
        assertEquals(listOf(TestLocalActor.urls.followers), announce.getValue("to").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(ActivityStreamsIri.PUBLIC_AUDIENCE, REMOTE_AUTHOR), announce.getValue("cc").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(null, page.next)
    }

    @Test
    fun `ページの境目で投稿もブーストも抜けず重ならない`() = runBlocking {
        val notes = (1..COLLECTION_PAGE_SIZE).map { note("note$it", "2026-11-01T00:00:${it.toString().padStart(2, '0')}Z") }
        val boosts = (1..COLLECTION_PAGE_SIZE).map { boost("boost$it", "2026-11-01T00:01:${it.toString().padStart(2, '0')}Z", BoostAudience.PUBLIC) }
        val endpoint = OutboxEndpoint(directory = directory, notes = ListNoteStore(notes), boosts = ListBoostStore(boosts), webPages = null)

        val first = page(endpoint, cursor = "")
        val nextCursor = checkNotNull(first.next).substringAfter("cursor=")
        val second = page(endpoint, cursor = java.net.URLDecoder.decode(nextCursor, Charsets.UTF_8))

        val ids = (first.orderedItems + second.orderedItems).map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(COLLECTION_PAGE_SIZE * 2, ids.toSet().size)
        assertEquals(COLLECTION_PAGE_SIZE.toLong() * 2, first.totalItems)
    }

    @Test
    fun `cursor が無ければ件数に投稿とブーストの両方を数える`() = runBlocking {
        val endpoint = OutboxEndpoint(
            directory = directory,
            notes = ListNoteStore(listOf(note("note", "2026-11-03T00:00:00Z"))),
            boosts = ListBoostStore(listOf(boost("boost", "2026-11-02T00:00:00Z", BoostAudience.PUBLIC))),
            webPages = null,
        )

        val collection = AppJson.decodeFromString(
            OrderedCollection.serializer(),
            endpoint.get(username = TestLocalActor.USERNAME, accept = null, cursor = null).body.decodeToString(),
        )

        assertEquals(2L, collection.totalItems)
    }

    private suspend fun page(endpoint: OutboxEndpoint, cursor: String): OrderedCollectionPage<JsonElement> = AppJson.decodeFromString(
        OrderedCollectionPage.serializer(JsonElement.serializer()),
        endpoint.get(username = TestLocalActor.USERNAME, accept = null, cursor = cursor).body.decodeToString(),
    )

    private fun note(publicId: String, publishedAt: String) = StoredNote(
        publicId = PublicNoteId(publicId),
        username = TestLocalActor.USERNAME,
        contentHtml = "<p>$publicId</p>",
        publishedAt = Instant.parse(publishedAt),
        attachments = listOf(),
        inReplyTo = null,
        mentions = listOf(),
    )

    private fun boost(key: String, boostedAt: String, audience: BoostAudience) = StoredBoost(
        announceId = "$ACTOR/announces/$key",
        objectUri = REMOTE_NOTE,
        objectAuthor = REMOTE_AUTHOR,
        boostedAt = Instant.parse(boostedAt),
        audience = audience,
    )

    private class ListNoteStore(private val notes: List<StoredNote>) : NoteStore {
        override fun find(publicId: PublicNoteId): StoredNote? = notes.firstOrNull { it.publicId == publicId }
        override fun delete(publicId: PublicNoteId) = Unit
        override fun deleteByUsername(username: String): Int = 0
        override fun findByPublicIds(publicIds: Set<PublicNoteId>): Map<PublicNoteId, StoredNote> = mapOf()
        override fun list(username: String, after: NotePosition?, limit: Int): List<StoredNote> = notes
            .sortedWith(compareByDescending<StoredNote> { it.publishedAt }.thenByDescending { it.publicId.value })
            .filter { after == null || it.publishedAt < after.publishedAt || (it.publishedAt == after.publishedAt && it.publicId.value < after.publicId.value) }
            .take(limit)
        override fun listPositions(username: String, after: NotePosition?, limit: Int): List<NotePosition> = listOf()
        override fun listAllPositions(after: NotePosition?, limit: Int): List<NotePosition> = listOf()
        override fun count(username: String): Long = notes.size.toLong()
        override fun counts(usernames: Set<String>): Map<String, Long> = mapOf()
    }

    private class ListBoostStore(private val boosts: List<StoredBoost>) : OutboxBoostStore {
        override fun list(username: String, after: OutboxPosition?, limit: Int): List<StoredBoost> = boosts
            .sortedWith(compareByDescending<StoredBoost> { it.boostedAt }.thenByDescending { it.announceId })
            .filter { after == null || it.boostedAt < after.at || (it.boostedAt == after.at && it.announceId < after.key) }
            .take(limit)
        override fun count(username: String): Long = boosts.size.toLong()
    }

    private companion object {
        val ACTOR = TestLocalActor.urls.actorId
        const val REMOTE_NOTE = "https://remote.example/notes/1"
        const val REMOTE_AUTHOR = "https://remote.example/users/bob"
    }
}
