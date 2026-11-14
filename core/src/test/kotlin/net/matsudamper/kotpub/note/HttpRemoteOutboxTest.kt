package net.matsudamper.kotpub.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.emoji.CustomEmoji
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.http.ResponseBody

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
            val body = if (document != null && readsBody(status, url)) ResponseBody.Read(document.encodeToByteArray()) else ResponseBody.NotRead
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
        assertEquals(listOf("$actorId/older", "$actorId/newer"), outbox.featured(actorId, 20).notes().map { it.id })
        assertEquals(listOf(actorId, featuredUrl), requests)
    }

    @Test
    fun featuredIsEmptyWhenActorHasNoFeaturedCollection() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        assertEquals(RemoteOutboxResult.Found(listOf()), outbox.featured(actorId, 20))
        assertEquals(listOf(actorId), requests)
    }

    @Test
    fun likedReadsObjectIdsFromStringsAndEmbeddedLikes() = runBlocking {
        val likedUrl = "$actorId/liked"
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl","liked":"$likedUrl"}"""
        documents[likedUrl] = """{"id":"$likedUrl","first":"$likedUrl?page=1"}"""
        documents["$likedUrl?page=1"] = """{"id":"$likedUrl?page=1","orderedItems":[
            "https://other.example/notes/1",
            {"type":"Like","object":"https://other.example/notes/2"},
            {"type":"Note","id":"https://other.example/notes/3"},
            "http://other.example/notes/4"
        ],"next":"$likedUrl?page=2"}"""
        documents["$likedUrl?page=2"] = """{"id":"$likedUrl?page=2","orderedItems":["https://other.example/notes/5"]}"""

        val page = assertIs<RemoteLikedPageResult.Found>(outbox.likedPage(actorId, null, 20)).page

        assertEquals(
            listOf("https://other.example/notes/1", "https://other.example/notes/2", "https://other.example/notes/3", "https://other.example/notes/5"),
            page.objectUris,
        )
        assertEquals(null, page.next)
    }

    @Test
    fun likedStopsAtLimitAndContinuesFromThere() = runBlocking {
        val likedUrl = "$actorId/liked"
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl","liked":"$likedUrl"}"""
        documents[likedUrl] = """{"id":"$likedUrl","orderedItems":["https://other.example/notes/1","https://other.example/notes/2","https://other.example/notes/3"]}"""

        val first = assertIs<RemoteLikedPageResult.Found>(outbox.likedPage(actorId, null, 2)).page
        val next = assertIs<RemoteLikedPageResult.Found>(outbox.likedPage(actorId, first.next, 2)).page

        assertEquals(listOf("https://other.example/notes/1", "https://other.example/notes/2"), first.objectUris)
        assertEquals(listOf("https://other.example/notes/3"), next.objectUris)
        assertEquals(null, next.next)
    }

    @Test
    fun likedIsAbsentWhenActorHasNoLikedCollection() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        val actor = assertIs<RemoteActorCollectionsLookup.Found>(outbox.collectionsOf(actorId)).collections

        assertEquals(false, actor.likedAvailable)
        assertEquals(RemoteLikedPageResult.Absent, outbox.likedPage(actor, null, 20))
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
        val notes = outbox.recent(actorId, 20).notes()
        assertEquals(listOf("$actorId/first"), notes.map { it.id })
        assertEquals(listOf(actorId, outboxUrl, "$outboxUrl?page=1"), requests)
    }

    @Test
    fun noteWithPublicOnlyInCcIsUnlisted() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[
            {"type":"Note","id":"$actorId/unlisted","attributedTo":"$actorId","content":"<p>投稿</p>","published":"2026-11-02T00:00:00Z",
                "to":["$actorId/followers"],"cc":["https://www.w3.org/ns/activitystreams#Public"]},
            ${note("public", actorId, true)}
        ]}"""
        assertEquals(listOf("$actorId/unlisted" to true, "$actorId/public" to false), outbox.recent(actorId, 20).notes().map { it.id to it.unlisted })
    }

    @Test
    fun doesNotFollowOutboxOnAnotherHost() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"https://other.example/outbox"}"""
        assertIs<RemoteFetchFailure.DisallowedHost>(assertIs<RemoteOutboxResult.Unavailable>(outbox.recent(actorId, 20)).failure)
        assertEquals(listOf(actorId), requests)
    }

    @Test
    fun distinguishesUnreachableCollectionFromEmptyOne() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<RemoteOutboxResult.Unavailable>(outbox.recent(actorId, 20)).failure)

        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<RemoteOutboxResult.Unavailable>(outbox.recent("https://remote.example/users/nobody", 20)).failure)

        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[]}"""
        assertEquals(RemoteOutboxResult.Found(listOf()), outbox.recent(actorId, 20))
    }

    @Test
    fun stopsAtRequestedLimitWithoutLoadingNextPage() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"orderedItems":[${note("first", actorId, true)}],"next":"$outboxUrl?page=2"}"""
        assertEquals(1, outbox.recent(actorId, 1).notes().size)
        assertEquals(listOf(actorId, outboxUrl), requests)
    }

    @Test
    fun doesNotCountCollectionEntryAsPage() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","first":"$outboxUrl?page=1"}"""
        documents["$outboxUrl?page=1"] = """{"id":"$outboxUrl?page=1","orderedItems":[],"next":"$outboxUrl?page=2"}"""
        documents["$outboxUrl?page=2"] = """{"id":"$outboxUrl?page=2","orderedItems":[],"next":"$outboxUrl?page=3"}"""
        documents["$outboxUrl?page=3"] = """{"id":"$outboxUrl?page=3","orderedItems":[${note("third", actorId, true)}],"next":"$outboxUrl?page=4"}"""
        assertEquals(listOf("$actorId/third"), outbox.recent(actorId, 20).notes().map { it.id })
        assertEquals(listOf(actorId, outboxUrl, "$outboxUrl?page=1", "$outboxUrl?page=2", "$outboxUrl?page=3"), requests)
    }

    @Test
    fun countsOnlyUniqueNotesTowardLimit() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[${note("a", actorId, true)}],"next":"$outboxUrl?page=2"}"""
        documents["$outboxUrl?page=2"] = """{"id":"$outboxUrl?page=2","orderedItems":[${note("a", actorId, true)},${note("b", actorId, true)}]}"""
        assertEquals(setOf("$actorId/a", "$actorId/b"), outbox.recent(actorId, 2).notes().map { it.id }.toSet())
    }

    @Test
    fun scansItemsBeyondNoteLimitInSamePage() = runBlocking {
        val boosts = (1..20).joinToString(",") { """{"type":"Announce","object":${note("boost$it", actorId, true)}}""" }
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[$boosts,${note("own", actorId, true)}]}"""
        assertEquals(listOf("$actorId/own"), outbox.recent(actorId, 1).notes().map { it.id })
    }

    @Test
    fun returnsAttachmentsAndContentWarning() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"orderedItems":[{"type":"Note","id":"$actorId/media","attributedTo":"$actorId","content":"<p>画像</p>",
            "published":"2026-11-01T00:00:00Z","to":["https://www.w3.org/ns/activitystreams#Public"],"summary":"注意","sensitive":true,
            "attachment":[{"type":"Document","mediaType":"image/png","url":"https://remote.example/a.png","name":"説明"}]}]}"""
        val note = outbox.recent(actorId, 20).notes().single()
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
        val note = outbox.recent(actorId, 20).notes().single()
        assertEquals(listOf(CustomEmoji(name = ":blob:", imageUrl = "https://remote.example/blob.png")), note.emojis)
    }

    @Test
    fun recentPageReturnsWholePageAndNextPageId() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","first":"$outboxUrl?page=1"}"""
        documents["$outboxUrl?page=1"] = """{"id":"$outboxUrl?page=1","orderedItems":[
            ${note("older", actorId, true, "2026-01-01T00:00:00Z")},
            ${note("newer", actorId, true, "2026-02-01T00:00:00Z")}
        ],"next":"$outboxUrl?page=2"}"""
        val page = recentPage(null)
        assertEquals(listOf("$actorId/newer", "$actorId/older"), page.notes.map { it.id })
        assertEquals(RemoteOutboxCursor("$outboxUrl?page=2", 0), page.next)
        assertEquals(listOf(actorId, outboxUrl, "$outboxUrl?page=1"), requests)
    }

    @Test
    fun recentPageReadsGivenPageWithoutActor() = runBlocking {
        documents["$outboxUrl?page=2"] = """{"id":"$outboxUrl?page=2","orderedItems":[${note("second", actorId, true)}]}"""
        val page = recentPage(RemoteOutboxCursor("$outboxUrl?page=2", 0))
        assertEquals(listOf("$actorId/second"), page.notes.map { it.id })
        assertEquals(null, page.next)
        assertEquals(listOf("$outboxUrl?page=2"), requests)
    }

    @Test
    fun recentPageSkipsPagesWithoutReadableNotes() = runBlocking {
        documents["$outboxUrl?page=1"] = """{"id":"$outboxUrl?page=1","orderedItems":[${note("private", actorId, false)}],"next":"$outboxUrl?page=2"}"""
        documents["$outboxUrl?page=2"] = """{"id":"$outboxUrl?page=2","orderedItems":[${note("second", actorId, true)}],"next":"$outboxUrl?page=3"}"""
        val page = recentPage(RemoteOutboxCursor("$outboxUrl?page=1", 0))
        assertEquals(listOf("$actorId/second"), page.notes.map { it.id })
        assertEquals(RemoteOutboxCursor("$outboxUrl?page=3", 0), page.next)
    }

    @Test
    fun recentPageSkipsIntoEmbeddedNextPageWithoutId() = runBlocking {
        documents["$outboxUrl?page=1"] = """{"id":"$outboxUrl?page=1","orderedItems":[],"next":{"orderedItems":[${note("embedded", actorId, true)}]}}"""
        val page = recentPage(RemoteOutboxCursor("$outboxUrl?page=1", 0))
        assertEquals(listOf("$actorId/embedded"), page.notes.map { it.id })
        assertEquals(null, page.next)
    }

    @Test
    fun recentPageResumesInsideLargePageWithoutDroppingItems() = runBlocking {
        val items = (1..350).joinToString(",") { note("n$it", actorId, true) }
        documents["$outboxUrl?page=1"] = """{"id":"$outboxUrl?page=1","orderedItems":[$items],"next":"$outboxUrl?page=2"}"""
        val first = recentPage(RemoteOutboxCursor("$outboxUrl?page=1", 0))
        assertEquals(300, first.notes.size)
        assertEquals(RemoteOutboxCursor("$outboxUrl?page=1", 300), first.next)

        val rest = recentPage(first.next)
        assertEquals((301..350).map { "$actorId/n$it" }.toSet(), rest.notes.map { it.id }.toSet())
        assertEquals(RemoteOutboxCursor("$outboxUrl?page=2", 0), rest.next)
    }

    @Test
    fun recentReadsBeyondHundredItemsInSamePageInsteadOfSkippingToNextPage() = runBlocking {
        val boosts = (1..120).joinToString(",") { """{"type":"Announce","object":${note("boost$it", actorId, true)}}""" }
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[$boosts,${note("own", actorId, true)}],"next":"$outboxUrl?page=2"}"""
        documents["$outboxUrl?page=2"] = """{"id":"$outboxUrl?page=2","orderedItems":[${note("older", actorId, true)}]}"""
        assertEquals(listOf("$actorId/own"), outbox.recent(actorId, 1).notes().map { it.id })
        assertEquals(listOf(actorId, outboxUrl), requests)
    }

    @Test
    fun recentStopsWithoutGapWhenItemLimitIsReached() = runBlocking {
        val boosts = (1..300).joinToString(",") { """{"type":"Announce","object":${note("boost$it", actorId, true)}}""" }
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[${note("newest", actorId, true)},$boosts,${note("beyond", actorId, true)}],"next":"$outboxUrl?page=2"}"""
        val result = assertIs<RemoteOutboxResult.Found>(outbox.recent(actorId, 20))
        assertEquals(listOf("$actorId/newest"), result.notes.map { it.id })
        assertEquals(RemoteOutboxIncomplete.ReadLimitReached(RemoteOutboxCursor(outboxUrl, 300)), result.incomplete)
        assertEquals(listOf(actorId, outboxUrl), requests)
    }

    @Test
    fun recentReturnsNotesReadBeforeNextPageBecameUnavailable() = runBlocking {
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[${note("newest", actorId, true)}],"next":"$outboxUrl?page=2"}"""
        val result = assertIs<RemoteOutboxResult.Found>(outbox.recent(actorId, 20))
        assertEquals(listOf("$actorId/newest"), result.notes.map { it.id })
        val incomplete = assertIs<RemoteOutboxIncomplete.PageUnavailable>(result.incomplete)
        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(incomplete.failure)
    }

    @Test
    fun recentPageReadsWholePageWithoutIdBecauseItCannotBeResumed() = runBlocking {
        val items = (1..350).joinToString(",") { note("n$it", actorId, true) }
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl"}"""
        documents[outboxUrl] = """{"first":{"orderedItems":[$items]}}"""
        val page = recentPage(null)
        assertEquals(350, page.notes.size)
        assertEquals(null, page.next)
    }

    @Test
    fun recentPageDoesNotReadPageOnAnotherHost() = runBlocking {
        documents["https://other.example/outbox?page=2"] = """{"orderedItems":[${note("other", actorId, true)}]}"""
        assertIs<RemoteFetchFailure.DisallowedHost>(assertIs<RemoteOutboxPageResult.Unavailable>(outbox.recentPage(actorId, RemoteOutboxCursor("https://other.example/outbox?page=2", 0))).failure)
        assertEquals(listOf(), requests)
    }

    @Test
    fun recentPageIsUnavailableWhenNextPageCannotBeRead() = runBlocking {
        documents["$outboxUrl?page=1"] = """{"id":"$outboxUrl?page=1","orderedItems":[],"next":"$outboxUrl?page=2"}"""
        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<RemoteOutboxPageResult.Unavailable>(outbox.recentPage(actorId, RemoteOutboxCursor("$outboxUrl?page=1", 0))).failure)
    }

    @Test
    fun recentPageAndFeaturedShareOneActorFetch() = runBlocking {
        val featuredUrl = "$actorId/collections/featured"
        documents[actorId] = """{"id":"$actorId","outbox":"$outboxUrl","featured":"$featuredUrl"}"""
        documents[outboxUrl] = """{"id":"$outboxUrl","orderedItems":[${note("recent", actorId, true)}]}"""
        documents[featuredUrl] = """{"id":"$featuredUrl","orderedItems":[${note("pinned", actorId, true)}]}"""
        val actor = assertIs<RemoteActorCollectionsLookup.Found>(outbox.collectionsOf(actorId)).collections

        val page = assertIs<RemoteOutboxPageResult.Found>(outbox.recentPage(actor, null)).page
        val featured = outbox.featured(actor, 20).notes()

        assertEquals(listOf("$actorId/recent"), page.notes.map { it.id })
        assertEquals(listOf("$actorId/pinned"), featured.map { it.id })
        assertEquals(listOf(actorId, outboxUrl, featuredUrl), requests)
    }

    @Test
    fun collectionsOfIsUnavailableWhenActorIdDiffers() = runBlocking {
        documents[actorId] = """{"id":"https://remote.example/users/mallory","outbox":"$outboxUrl"}"""
        assertIs<RemoteFetchFailure.InvalidDocument>(assertIs<RemoteActorCollectionsLookup.Unavailable>(outbox.collectionsOf(actorId)).failure)
        assertIs<RemoteFetchFailure.InvalidDocument>(assertIs<RemoteOutboxPageResult.Unavailable>(outbox.recentPage(actorId, null)).failure)
    }

    @Test
    fun collectionsOfIsUnavailableWhenActorCannotBeRead() = runBlocking {
        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<RemoteActorCollectionsLookup.Unavailable>(outbox.collectionsOf(actorId)).failure)
        assertIs<RemoteFetchFailure.NotHttps>(assertIs<RemoteActorCollectionsLookup.Unavailable>(outbox.collectionsOf("http://remote.example/users/alice")).failure)
        assertEquals(listOf(actorId), requests)
    }

    private suspend fun recentPage(from: RemoteOutboxCursor?): RemoteOutboxPage = assertIs<RemoteOutboxPageResult.Found>(outbox.recentPage(actorId, from)).page

    private fun note(id: String, author: String, public: Boolean): String = note(id, author, public, "2026-11-01T00:00:00Z")

    private fun note(id: String, author: String, public: Boolean, published: String): String =
        """{"type":"Note","id":"$actorId/$id","attributedTo":"$author","content":"<p>投稿</p>","published":"$published","to":["${if (public) "https://www.w3.org/ns/activitystreams#Public" else actorId}"]}"""

    private fun RemoteOutboxResult.notes(): List<RemoteOutboxNote> =
        when (this) {
            is RemoteOutboxResult.Found -> notes
            is RemoteOutboxResult.Unavailable -> error("取得できなかった: $failure")
        }
}
