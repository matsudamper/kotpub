package net.matsudamper.kotpub.inbox

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.FakeFollowerStore
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.note.ReceivedNote
import net.matsudamper.kotpub.note.RemoteNoteStore

class UpdateHandlerTest {
    @Test
    fun `編集された投稿は署名者と同じホストの署名者の投稿だけを渡す`() = runBlocking {
        val notes = RecordingNotes()
        val handler = UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes)
        receive(handler, """"https://remote.example/notes/1"""")
        receive(handler, note(author = "https://other.example/actor", id = "https://remote.example/notes/1"))
        receive(handler, note(author = ACTOR, id = "https://other.example/notes/1"))
        receive(handler, """{"type":"Question","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","content":"<p>poll</p>"}""")
        assertTrue(notes.updated.isEmpty())

        receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1"))

        val updated = notes.updated.single()
        assertEquals("<p>edited</p>", updated.content)
        assertEquals(Instant.parse("2026-08-10T01:00:00Z"), updated.updated)
    }

    @Test
    fun `id の無い Link の Update は Update の id を投稿の id にしない`() = runBlocking {
        val notes = RecordingNotes()
        val handler = UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes)
        val raw = AppJson.parseToJsonElement(
            """{"id":"https://remote.example/activities/2","type":"Update","actor":"$ACTOR","object":{"type":"Link","href":"https://example.com/"}}""",
        ).jsonObject

        handler.handle(InboxRecipient.Shared, ACTOR, AppJson.decodeFromJsonElement(InboxActivity.serializer(), raw), raw)

        assertTrue(notes.updated.isEmpty())
    }

    @Test
    fun `送り主自身の Update はプロフィールの編集として扱う`() = runBlocking {
        val notes = RecordingNotes()
        val handler = UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes)

        val handling = receive(handler, """{"id":"$ACTOR","type":"Person","preferredUsername":"alice","name":"アリス（改名）"}""")

        assertEquals(InboxHandling.Handled, handling)
        assertTrue(notes.updated.isEmpty())
    }

    private suspend fun receive(handler: UpdateHandler, target: String): InboxHandling {
        val raw = AppJson.parseToJsonElement("""{"type":"Update","actor":"$ACTOR","object":$target}""").jsonObject
        return handler.handle(InboxRecipient.Shared, ACTOR, AppJson.decodeFromJsonElement(InboxActivity.serializer(), raw), raw)
    }

    private fun note(author: String, id: String): String =
        """{"type":"Note","id":"$id","attributedTo":"$author","content":"<p>edited</p>","to":["$PUBLIC"],"updated":"2026-08-10T01:00:00Z"}"""

    private class RecordingNotes : RemoteNoteStore {
        val updated = mutableListOf<ReceivedNote>()

        override suspend fun save(note: ReceivedNote) = Unit

        override suspend fun update(note: ReceivedNote) {
            updated.add(note)
        }

        override suspend fun delete(actorId: String, objectId: String) = Unit
    }

    private companion object {
        const val ACTOR = "https://remote.example/actor"
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
    }
}
