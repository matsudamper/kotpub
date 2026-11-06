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
import net.matsudamper.kotpub.note.ReceivedNote
import net.matsudamper.kotpub.note.RemoteNoteStore

class DeleteHandlerTest {
    @Test
    fun deleteRoutesActorsAndOnlyAcceptsNotesFromTheSameHost() = runBlocking {
        val deleted = mutableListOf<String>()
        val notes = object : RemoteNoteStore {
            override suspend fun save(note: ReceivedNote) = Unit
            override suspend fun delete(actorId: String, objectId: String) {
                deleted.add(objectId)
            }
        }
        val handler = DeleteHandler(DeleteActorHandler(FakeFollowerStore(), FakeFavouriteStore(), FakeStampStore(), FakeBoostStore()), notes)
        listOf(ACTOR, "https://other.example/note", "https://remote.example/notes/1").forEach { id ->
            handler.handle(InboxRecipient.Shared, ACTOR, InboxActivity(type = "Delete", actor = LinkOrObject.Link(ACTOR), target = LinkOrObject.Link(id)), JsonObject(mapOf()))
        }
        listOf("Note", "Tombstone", "Person").forEach { type ->
            val target = LinkOrObject.Embedded(JsonObject(mapOf("type" to JsonPrimitive(type), "id" to JsonPrimitive("https://remote.example/$type"))))
            handler.handle(InboxRecipient.Shared, ACTOR, InboxActivity(type = "Delete", actor = LinkOrObject.Link(ACTOR), target = target), JsonObject(mapOf()))
        }
        assertEquals(listOf("https://remote.example/notes/1", "https://remote.example/Note", "https://remote.example/Tombstone"), deleted)
    }

    private companion object {
        const val ACTOR = "https://remote.example/actor"
    }
}
