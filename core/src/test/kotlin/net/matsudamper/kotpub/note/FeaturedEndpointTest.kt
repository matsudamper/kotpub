package net.matsudamper.kotpub.note

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.FakeStoredActorNames
import net.matsudamper.kotpub.TestLocalActor
import net.matsudamper.kotpub.TestWebPageUrls
import net.matsudamper.kotpub.actor.ActorDirectory
import net.matsudamper.kotpub.collection.FEATURED_COLLECTION_SIZE
import net.matsudamper.kotpub.collection.OrderedCollectionWithItems
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.json.AppJson

class FeaturedEndpointTest {
    private val directory = ActorDirectory(
        domain = TestLocalActor.DOMAIN,
        stored = FakeStoredActorNames(listOf(TestLocalActor.USERNAME)),
    )

    private val pinned = StoredNote(
        publicId = PublicNoteId("pinned"),
        username = TestLocalActor.USERNAME,
        contentHtml = "<p>固定した投稿</p>",
        publishedAt = Instant.parse("2026-08-10T00:00:00Z"),
        attachments = listOf(),
        inReplyTo = null,
        mentions = listOf(),
    )

    @Test
    fun `固定した投稿を渡された順に並べる`() = runBlocking {
        val requested = mutableListOf<Pair<String, Int>>()
        val endpoint = FeaturedEndpoint(
            directory = directory,
            featuredNotes = { username, limit ->
                requested.add(username to limit)
                listOf(pinned, pinned.copy(publicId = PublicNoteId("older"), contentHtml = "<p>前に固定した投稿</p>"))
            },
            webPages = TestWebPageUrls,
        )

        val response = endpoint.get(username = "ADMIN", accept = null)

        assertEquals(HttpStatusCodes.OK, response.status)
        assertEquals(listOf(TestLocalActor.USERNAME to FEATURED_COLLECTION_SIZE), requested)
        val collection = AppJson.decodeFromString(OrderedCollectionWithItems.serializer(Note.serializer()), response.body.decodeToString())
        assertEquals(TestLocalActor.urls.featured, collection.id)
        assertEquals(2L, collection.totalItems)
        assertEquals(listOf("<p>固定した投稿</p>", "<p>前に固定した投稿</p>"), collection.orderedItems.map { it.content })
        assertEquals(NoteUrls(domain = TestLocalActor.DOMAIN, publicId = PublicNoteId("pinned")).noteId, collection.orderedItems.first().id)
        assertEquals(null, collection.orderedItems.first().context)
    }

    @Test
    fun `固定した投稿が無ければ空を返す`() = runBlocking {
        val endpoint = FeaturedEndpoint(directory = directory, featuredNotes = { _, _ -> listOf() }, webPages = null)

        val response = endpoint.get(username = TestLocalActor.USERNAME, accept = null)

        val collection = AppJson.decodeFromString(OrderedCollectionWithItems.serializer(Note.serializer()), response.body.decodeToString())
        assertEquals(0L, collection.totalItems)
        assertEquals(listOf(), collection.orderedItems)
    }

    @Test
    fun `知らないアカウントは 404`() = runBlocking {
        val endpoint = FeaturedEndpoint(directory = directory, featuredNotes = { _, _ -> listOf(pinned) }, webPages = null)

        assertEquals(HttpStatusCodes.NOT_FOUND, endpoint.get(username = "other", accept = null).status)
    }
}
