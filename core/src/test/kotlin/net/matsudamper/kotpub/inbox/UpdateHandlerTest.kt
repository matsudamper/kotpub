package net.matsudamper.kotpub.inbox

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.FakeFollowerStore
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.note.ReceivedNote
import net.matsudamper.kotpub.note.ReceivedNoteJson
import net.matsudamper.kotpub.note.RemoteNoteLookup
import net.matsudamper.kotpub.note.RemoteNoteStore
import net.matsudamper.kotpub.note.RemoteNotes
import net.matsudamper.kotpub.note.UnknownTypeStore

class UpdateHandlerTest {
    @Test
    fun `編集された投稿は署名者と同じホストの署名者の投稿だけを渡す`() = runBlocking {
        val notes = RecordingNotes()
        val handler = UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes, UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        receive(handler, """"https://remote.example/notes/1"""")
        receive(handler, note(author = "https://other.example/actor", id = "https://remote.example/notes/1"))
        receive(handler, note(author = ACTOR, id = "https://other.example/notes/1"))
        receive(handler, """{"id":"https://remote.example/notes/1","attributedTo":"$ACTOR","content":"<p>no type</p>"}""")
        assertTrue(notes.updated.isEmpty())

        receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1"))

        val updated = notes.updated.single()
        assertEquals("<p>edited</p>", updated.content)
        assertEquals(Instant.parse("2026-08-10T01:00:00Z"), updated.updated)
    }

    @Test
    fun `保存済みの投稿者と違う Update は投稿のあるサーバーの投稿者で判断する`() = runBlocking {
        val notes = RecordingNotes(savedAuthors = mapOf("https://remote.example/notes/1" to "https://remote.example/other"))
        val edited = note(author = ACTOR, id = "https://remote.example/notes/1")

        receive(UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes, UnknownTypeStore { _, _ -> }, originAuthoredBy("https://remote.example/other")), edited)
        assertTrue(notes.updated.isEmpty())

        receive(UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes, UnknownTypeStore { _, _ -> }, originAuthoredBy(ACTOR)), edited)
        assertEquals(ACTOR, notes.updated.single().attributedTo)
    }

    @Test
    fun `判断した後に保存済みの投稿者が書き換わったら編集を渡さず送り直させる`() = runBlocking {
        val notes = RecordingNotes(
            savedAuthors = mapOf("https://remote.example/notes/1" to ACTOR),
            authorsAtWrite = mapOf("https://remote.example/notes/1" to "https://remote.example/other"),
        )
        val handler = UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes, UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)

        val received = runCatching { receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1")) }

        assertTrue(received.isFailure)
        assertTrue(notes.updated.isEmpty())
    }

    @Test
    fun `知らない型の Update は投稿の編集にせず型と出どころを記録する`() = runBlocking {
        val notes = RecordingNotes()
        val recorded = mutableListOf<Pair<List<String>, String>>()
        val handler = UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes, UnknownTypeStore { types, sourceUrl -> recorded.add(types to sourceUrl) }, NOT_FOUND_ORIGIN)

        receive(handler, """{"type":"Article","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","content":"<p>article</p>"}""")

        assertTrue(notes.updated.isEmpty())
        assertEquals(listOf(listOf("Article") to "https://remote.example/notes/1"), recorded)
    }

    @Test
    fun `id の無い Link の Update は Update の id を投稿の id にしない`() = runBlocking {
        val notes = RecordingNotes()
        val handler = UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes, UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val raw = AppJson.parseToJsonElement(
            """{"id":"https://remote.example/activities/2","type":"Update","actor":"$ACTOR","object":{"type":"Link","href":"https://example.com/"}}""",
        ).jsonObject

        handler.handle(InboxRecipient.Shared, ACTOR, AppJson.decodeFromJsonElement(InboxActivity.serializer(), raw), raw)

        assertTrue(notes.updated.isEmpty())
    }

    @Test
    fun `投票の票数が変わった Question の Update も投稿の編集として渡す`() = runBlocking {
        val notes = RecordingNotes()
        val handler = UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes, UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val options = """[{"type":"Note","name":"犬","replies":{"type":"Collection","totalItems":2}}]"""

        receive(handler, """{"type":"Question","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","content":"<p>poll</p>","oneOf":$options}""")

        assertEquals(listOf(2), notes.updated.single().poll?.options?.map { it.votesCount })
    }

    @Test
    fun `送り主自身の Update はプロフィールの編集として扱う`() = runBlocking {
        val notes = RecordingNotes()
        val handler = UpdateHandler(UpdateActorHandler(FakeFollowerStore()), notes, UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)

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

    private fun originAuthoredBy(author: String): RemoteNotes = RemoteNotes { noteId ->
        RemoteNoteLookup.Found(ReceivedNoteJson.read(AppJson.parseToJsonElement(note(author = author, id = noteId)).jsonObject) ?: error("テストの投稿を読めない"))
    }

    /**
     * @param authorsAtWrite 書き込む時点の保存済みの投稿者。判断した後に別の書き込みが割り込んだ状態を作る
     */
    private class RecordingNotes(
        private val savedAuthors: Map<String, String> = mapOf(),
        private val authorsAtWrite: Map<String, String> = savedAuthors,
    ) : RemoteNoteStore {
        val updated = mutableListOf<ReceivedNote>()

        override suspend fun findAttributedTo(noteId: String): String? = savedAuthors[noteId]

        override suspend fun save(note: ReceivedNote, expectedAttributedTo: String?): Boolean = true

        override suspend fun update(note: ReceivedNote, expectedAttributedTo: String?): Boolean {
            if (authorsAtWrite[note.id] != expectedAttributedTo) return false
            updated.add(note)
            return true
        }

        override suspend fun delete(actorId: String, objectId: String, expectedAttributedTo: String?): Boolean = true
    }

    private companion object {
        const val ACTOR = "https://remote.example/actor"
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
        val NOT_FOUND_ORIGIN = RemoteNotes { noteId -> RemoteNoteLookup.NotFound(RemoteFetchFailure.InvalidDocument(noteId, "投稿のあるサーバーに無い")) }
    }
}
