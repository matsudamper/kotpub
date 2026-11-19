package net.matsudamper.kotpub.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.FakeBoostStore
import net.matsudamper.kotpub.FakeFavouriteStore
import net.matsudamper.kotpub.FakeFollowerStore
import net.matsudamper.kotpub.FakeStampStore
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.note.ReceivedNote
import net.matsudamper.kotpub.note.ReceivedNoteJson
import net.matsudamper.kotpub.note.RemoteNoteLookup
import net.matsudamper.kotpub.note.RemoteNoteStore
import net.matsudamper.kotpub.note.RemoteNotes

class DeleteHandlerTest {
    @Test
    fun deleteRoutesActorsAndOnlyAcceptsNotesFromTheSameHost() {
        runBlocking {
            val deleted = mutableListOf<String>()
            val notes = object : RemoteNoteStore {
                override suspend fun findAttributedTo(noteId: String): String? = null
                override suspend fun save(note: ReceivedNote, expectedAttributedTo: String?): Boolean = true
                override suspend fun update(note: ReceivedNote, expectedAttributedTo: String?): Boolean = true
                override suspend fun delete(actorId: String, objectId: String, expectedAttributedTo: String?): Boolean {
                    deleted.add(objectId)
                    return true
                }
            }
            val handler = DeleteHandler(DeleteActorHandler(FakeFollowerStore(), FakeFavouriteStore(), FakeStampStore(), FakeBoostStore()), notes, NOT_FOUND_ORIGIN)
            listOf(ACTOR, "https://other.example/note", "https://remote.example/notes/1").forEach { id ->
                handler.handle(InboxRecipient.Shared, ACTOR, InboxActivity(type = "Delete", actor = LinkOrObject.Link(ACTOR), target = LinkOrObject.Link(id)), JsonObject(mapOf()))
            }
            listOf("Note", "Question", "Link", "Tombstone", "Person").forEach { type ->
                val target = LinkOrObject.Embedded(JsonObject(mapOf("type" to JsonPrimitive(type), "id" to JsonPrimitive("https://remote.example/$type"))))
                handler.handle(InboxRecipient.Shared, ACTOR, InboxActivity(type = "Delete", actor = LinkOrObject.Link(ACTOR), target = target), JsonObject(mapOf()))
            }
            assertEquals(
                listOf(
                    "https://remote.example/notes/1",
                    "https://remote.example/Note",
                    "https://remote.example/Question",
                    "https://remote.example/Link",
                    "https://remote.example/Tombstone",
                ),
                deleted,
            )
        }
    }

    @Test
    fun `保存済みの投稿者と違う Delete は投稿のあるサーバーが投稿者を示すか投稿は無いと答えたときだけ渡し、取得できなければ送り直させる`() {
        runBlocking {
            val noteId = "https://remote.example/notes/1"
            val otherAuthor = "https://remote.example/other"
            val authoredByOther = RemoteNotes { RemoteNoteLookup.Found(receivedNote(id = it, author = otherAuthor)) }
            val authoredBySigner = RemoteNotes { RemoteNoteLookup.Found(receivedNote(id = it, author = ACTOR)) }
            val unavailable = RemoteNotes { RemoteNoteLookup.Unavailable(RemoteFetchFailure.Throttled(it)) }
            val unauthorized = RemoteNotes { RemoteNoteLookup.NotFound(RemoteFetchFailure.UnsuccessfulStatus(it, 401)) }
            val gone = RemoteNotes { RemoteNoteLookup.NotFound(RemoteFetchFailure.UnsuccessfulStatus(it, 410)) }

            val deletedByOrigin = listOf(authoredByOther, unavailable, unauthorized, NOT_FOUND_ORIGIN, authoredBySigner, gone).map { origin ->
                val deleted = mutableListOf<String>()
                val notes = object : RemoteNoteStore {
                    override suspend fun findAttributedTo(noteId: String): String? = otherAuthor
                    override suspend fun save(note: ReceivedNote, expectedAttributedTo: String?): Boolean = true
                    override suspend fun update(note: ReceivedNote, expectedAttributedTo: String?): Boolean = true
                    override suspend fun delete(actorId: String, objectId: String, expectedAttributedTo: String?): Boolean {
                        if (expectedAttributedTo != otherAuthor) return false
                        deleted.add(objectId)
                        return true
                    }
                }
                val handler = DeleteHandler(DeleteActorHandler(FakeFollowerStore(), FakeFavouriteStore(), FakeStampStore(), FakeBoostStore()), notes, origin)
                val handled = runCatching {
                    handler.handle(
                        InboxRecipient.Shared,
                        ACTOR,
                        InboxActivity(type = "Delete", actor = LinkOrObject.Link(ACTOR), target = LinkOrObject.Link(noteId)),
                        JsonObject(mapOf()),
                    )
                }
                handled.isFailure to deleted.toList()
            }

            assertEquals(
                listOf(
                    false to listOf(),
                    true to listOf(),
                    false to listOf(),
                    false to listOf(),
                    false to listOf(noteId),
                    false to listOf(noteId),
                ),
                deletedByOrigin,
            )
        }
    }

    private fun receivedNote(id: String, author: String): ReceivedNote =
        ReceivedNoteJson.read(
            JsonObject(mapOf("type" to JsonPrimitive("Note"), "id" to JsonPrimitive(id), "attributedTo" to JsonPrimitive(author), "content" to JsonPrimitive("<p>note</p>"))),
        ) ?: error("テストの投稿を読めない")

    private companion object {
        const val ACTOR = "https://remote.example/actor"
        val NOT_FOUND_ORIGIN = RemoteNotes { noteId -> RemoteNoteLookup.NotFound(RemoteFetchFailure.InvalidDocument(noteId, "投稿のあるサーバーに無い")) }
    }
}
