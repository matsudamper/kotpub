package net.matsudamper.kotpub.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.http.ResponseBody

class HttpRemoteNoteCountsTest {
    private val noteId = "https://remote.example/notes/1"
    private val repliesUrl = "$noteId/replies"
    private val requests = mutableListOf<String>()
    private val documents = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private val client = object : ActivityPubHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>, maxBodyBytes: Int, readsBody: (Int, String) -> Boolean): ActivityPubClientResponse {
            requests.add(url)
            val document = documents[url]
            val status = statuses[url] ?: if (document == null) 404 else 200
            val body = if (document != null && readsBody(status, url)) ResponseBody.Read(document.encodeToByteArray()) else ResponseBody.NotRead
            return ActivityPubClientResponse(status, url, body)
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): ActivityPubPostResponse = error("POST は不要")

        override fun close() = Unit
    }
    private val counts = HttpRemoteNoteCounts(client)

    private fun note(extraFields: String): String =
        """{"type":"Note","id":"$noteId","attributedTo":"https://remote.example/users/alice","content":"<p>投稿</p>",
            "to":["https://www.w3.org/ns/activitystreams#Public"]$extraFields}"""

    @Test
    fun countsMastodonRepliesByFollowingEmbeddedFirstPageAndNext() = runBlocking {
        documents[noteId] = note(
            ""","replies":{"id":"$repliesUrl","type":"Collection","first":{"type":"CollectionPage",
            "next":"$repliesUrl?page=1","partOf":"$repliesUrl","items":["https://remote.example/notes/2"]}},
            "likes":{"id":"$noteId/likes","type":"Collection","totalItems":5},
            "shares":{"id":"$noteId/shares","type":"Collection","totalItems":3}""",
        )
        documents["$repliesUrl?page=1"] = """{"id":"$repliesUrl?page=1","type":"CollectionPage",
            "next":"$repliesUrl?page=2","items":["https://other.example/notes/1","https://other.example/notes/2"]}"""
        documents["$repliesUrl?page=2"] = """{"id":"$repliesUrl?page=2","type":"CollectionPage","items":["https://other.example/notes/3"]}"""

        val found = assertIs<RemoteNoteCountsResult.Found>(counts.count(noteId, 5))

        assertEquals(RemoteNoteCountsResult.Found(replies = RemoteReplyCount(count = 4, complete = true), likes = 5, shares = 3), found)
        assertEquals(listOf(noteId, "$repliesUrl?page=1", "$repliesUrl?page=2"), requests)
    }

    @Test
    fun stopsAtMaxReplyPagesAndReportsIncomplete() = runBlocking {
        documents[noteId] = note(""","replies":{"id":"$repliesUrl","first":{"next":"$repliesUrl?page=1","items":["https://remote.example/notes/2"]}}""")
        documents["$repliesUrl?page=1"] = """{"id":"$repliesUrl?page=1","next":"$repliesUrl?page=2","items":["https://other.example/notes/1"]}"""

        val found = assertIs<RemoteNoteCountsResult.Found>(counts.count(noteId, 2))

        assertEquals(RemoteReplyCount(count = 2, complete = false), found.replies)
        assertEquals(listOf(noteId, "$repliesUrl?page=1"), requests)
    }

    @Test
    fun usesTotalItemsOfRepliesAndFetchesCollectionsGivenByUrl() = runBlocking {
        documents[noteId] = note(""","replies":"$repliesUrl","likes":"$noteId/likes","shares":"$noteId/shares"""")
        documents[repliesUrl] = """{"id":"$repliesUrl","type":"OrderedCollection","totalItems":12,"first":"$repliesUrl?page=1"}"""
        documents["$noteId/likes"] = """{"id":"$noteId/likes","type":"Collection","totalItems":7}"""
        documents["$noteId/shares"] = """{"id":"$noteId/shares","type":"Collection","first":"$noteId/shares?page=1"}"""

        val found = assertIs<RemoteNoteCountsResult.Found>(counts.count(noteId, 5))

        assertEquals(RemoteNoteCountsResult.Found(replies = RemoteReplyCount(count = 12, complete = true), likes = 7, shares = null), found)
        assertEquals(listOf(noteId, repliesUrl, "$noteId/likes", "$noteId/shares"), requests)
    }

    @Test
    fun returnsNullForCollectionsTheNoteDoesNotExpose() = runBlocking {
        documents[noteId] = note("")

        assertEquals(RemoteNoteCountsResult.Found(replies = null, likes = null, shares = null), counts.count(noteId, 5))
        assertEquals(listOf(noteId), requests)
    }

    @Test
    fun doesNotReadCollectionsOnAnotherHost() = runBlocking {
        documents[noteId] = note(""","replies":"https://other.example/replies","likes":"https://other.example/likes"""")
        documents["https://other.example/replies"] = """{"id":"https://other.example/replies","totalItems":100}"""
        documents["https://other.example/likes"] = """{"id":"https://other.example/likes","totalItems":100}"""

        assertEquals(RemoteNoteCountsResult.Found(replies = null, likes = null, shares = null), counts.count(noteId, 5))
        assertEquals(listOf(noteId), requests)
    }

    @Test
    fun reportsIncompleteWhenNextPageIsOnAnotherHostOrUnreadable() = runBlocking {
        documents[noteId] = note(""","replies":{"first":{"next":"https://other.example/replies?page=1","items":["https://remote.example/notes/2"]}}""")

        val found = assertIs<RemoteNoteCountsResult.Found>(counts.count(noteId, 5))

        assertEquals(RemoteReplyCount(count = 1, complete = false), found.replies)
        assertEquals(listOf(noteId), requests)
    }

    @Test
    fun distinguishesTemporaryFailureFromMissingNote() = runBlocking<Unit> {
        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<RemoteNoteCountsResult.NotFound>(counts.count(noteId, 5)).failure)

        statuses[noteId] = 503
        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<RemoteNoteCountsResult.Unavailable>(counts.count(noteId, 5)).failure)
    }
}
