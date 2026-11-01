package net.matsudamper.kotpub.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.note.NoteVisibility
import net.matsudamper.kotpub.note.ReceivedNote
import net.matsudamper.kotpub.note.RemoteNoteStore

class CreateNoteHandlerTest {
    @Test
    fun embeddedNotesRequireMatchingAuthorAndHost() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store)
        receive(handler, """"https://remote.example/notes/1"""")
        receive(handler, note(author = "https://other.example/actor", id = "https://remote.example/notes/1", to = PUBLIC, cc = ""))
        receive(handler, note(author = ACTOR, id = "https://other.example/notes/1", to = PUBLIC, cc = ""))
        assertTrue(store.saved.isEmpty())
        receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1", to = PUBLIC, cc = ""))
        assertEquals(NoteVisibility.Public, store.saved.single().visibility)
        assertEquals("<p>hello</p>", store.saved.single().content)
    }

    @Test
    fun visibilityUsesToBeforeCcAndKeepsPrivateRecipients() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store)
        listOf(PUBLIC to "", "local" to PUBLIC, "$ACTOR/followers" to "", "local" to "").forEach { (to, cc) ->
            receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1", to = to, cc = cc))
        }
        assertEquals(NoteVisibility.entries.toList(), store.saved.map { it.visibility })
        assertEquals(listOf("local"), store.saved.last().to)
    }

    private suspend fun receive(handler: CreateNoteHandler, target: String) {
        val raw = AppJson.parseToJsonElement("""{"type":"Create","actor":"$ACTOR","object":$target}""").jsonObject
        handler.handle(InboxRecipient.Shared, ACTOR, AppJson.decodeFromJsonElement(InboxActivity.serializer(), raw), raw)
    }

    private fun note(author: String, id: String, to: String, cc: String): String =
        """{"type":"Note","id":"$id","attributedTo":"$author","content":"<p>hello</p>","to":["$to"],"cc":["$cc"]}"""

    private class RecordingNotes : RemoteNoteStore {
        val saved = mutableListOf<ReceivedNote>()

        override suspend fun save(note: ReceivedNote) {
            saved.add(note)
        }

        override suspend fun delete(actorId: String, objectId: String) = Unit
    }

    private companion object {
        const val ACTOR = "https://remote.example/actor"
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
    }
}
