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

class HttpRemoteNotesTest {
    private val noteId = "https://remote.example/notes/1"
    private val documents = mutableMapOf<String, String>()
    private val redirects = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private val client = object : ActivityPubHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>, maxBodyBytes: Int, readsBody: (Int, String) -> Boolean): ActivityPubClientResponse {
            val finalUrl = redirects[url] ?: url
            val document = documents[finalUrl]
            val status = statuses[finalUrl] ?: if (document == null) 404 else 200
            val body = if (document != null && readsBody(status, finalUrl)) ResponseBody.Read(document.encodeToByteArray()) else ResponseBody.NotRead
            return ActivityPubClientResponse(status, finalUrl, body)
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): ActivityPubPostResponse = error("POST は不要")

        override fun close() = Unit
    }
    private val notes = HttpRemoteNotes(client)

    private fun note(id: String, author: String): String =
        """{"type":"Note","id":"$id","attributedTo":"$author","content":"<p>投稿</p>","to":["https://www.w3.org/ns/activitystreams#Public"]}"""

    @Test
    fun readsNoteWhoseIdAndAuthorMatchRequestedHost() = runBlocking {
        documents[noteId] = note(noteId, "https://remote.example/users/alice")

        val note = (notes.find(noteId) as? RemoteNoteLookup.Found)?.note

        assertEquals(noteId, note?.id)
        assertEquals(NoteVisibility.Public, note?.visibility)
    }

    @Test
    fun rejectsNoteClaimingAnotherIdOrAuthorOnAnotherHost() = runBlocking {
        documents[noteId] = note("https://remote.example/notes/2", "https://remote.example/users/alice")
        assertIs<RemoteFetchFailure.InvalidDocument>(assertIs<RemoteNoteLookup.NotFound>(notes.find(noteId)).failure)

        documents[noteId] = note(noteId, "https://other.example/users/alice")
        assertIs<RemoteFetchFailure.InvalidDocument>(assertIs<RemoteNoteLookup.NotFound>(notes.find(noteId)).failure)
    }

    @Test
    fun distinguishesTemporaryFailureFromMissingNote() = runBlocking {
        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<RemoteNoteLookup.NotFound>(notes.find(noteId)).failure)

        statuses[noteId] = 503
        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<RemoteNoteLookup.Unavailable>(notes.find(noteId)).failure)

        statuses[noteId] = 429
        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<RemoteNoteLookup.Unavailable>(notes.find(noteId)).failure)
    }

    @Test
    fun rejectsRedirectToAnotherHostAndPlainHttp() = runBlocking {
        val otherUrl = "https://other.example/notes/1"
        redirects[noteId] = otherUrl
        documents[otherUrl] = note(noteId, "https://remote.example/users/alice")
        assertIs<RemoteFetchFailure.RedirectedToDisallowedUrl>(assertIs<RemoteNoteLookup.NotFound>(notes.find(noteId)).failure)

        documents["http://remote.example/notes/3"] = note("http://remote.example/notes/3", "http://remote.example/users/alice")
        assertIs<RemoteFetchFailure.NotHttps>(assertIs<RemoteNoteLookup.NotFound>(notes.find("http://remote.example/notes/3")).failure)
    }
}
