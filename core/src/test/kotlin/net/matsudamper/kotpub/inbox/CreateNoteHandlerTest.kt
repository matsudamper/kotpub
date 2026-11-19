package net.matsudamper.kotpub.inbox

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.emoji.CustomEmoji
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.note.NoteVisibility
import net.matsudamper.kotpub.note.PollVoteStore
import net.matsudamper.kotpub.note.ReceivedNote
import net.matsudamper.kotpub.note.ReceivedNoteAttachment
import net.matsudamper.kotpub.note.ReceivedNoteJson
import net.matsudamper.kotpub.note.ReceivedPoll
import net.matsudamper.kotpub.note.ReceivedPollOption
import net.matsudamper.kotpub.note.ReceivedPollVote
import net.matsudamper.kotpub.note.RemoteNoteLookup
import net.matsudamper.kotpub.note.RemoteNoteStore
import net.matsudamper.kotpub.note.RemoteNotes
import net.matsudamper.kotpub.note.UnknownTypeStore

class CreateNoteHandlerTest {
    @Test
    fun embeddedNotesRequireMatchingAuthorAndHost() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        receive(handler, """"https://remote.example/notes/1"""")
        receive(handler, note(author = "https://other.example/actor", id = "https://remote.example/notes/1", to = PUBLIC, cc = ""))
        receive(handler, note(author = ACTOR, id = "https://other.example/notes/1", to = PUBLIC, cc = ""))
        assertTrue(store.saved.isEmpty())
        receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1", to = PUBLIC, cc = ""))
        assertEquals(NoteVisibility.Public, store.saved.single().visibility)
        assertEquals("<p>hello</p>", store.saved.single().content)
    }

    @Test
    fun `保存済みの投稿者と違う Create は投稿のあるサーバーの投稿者で判断する`() = runBlocking {
        val store = RecordingNotes(savedAuthors = mapOf("https://remote.example/notes/1" to "https://remote.example/other"))
        val created = note(author = ACTOR, id = "https://remote.example/notes/1", to = PUBLIC, cc = "")

        receive(CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, originAuthoredBy("https://remote.example/other")), created)
        assertTrue(store.saved.isEmpty())

        receive(CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, originAuthoredBy(ACTOR)), created)
        assertEquals(ACTOR, store.saved.single().attributedTo)
    }

    @Test
    fun `判断した後に保存済みの投稿者が書き換わったら保存せず送り直させる`() = runBlocking {
        val store = RecordingNotes(authorsAtWrite = mapOf("https://remote.example/notes/1" to "https://remote.example/other"))
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)

        val received = runCatching { receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1", to = PUBLIC, cc = "")) }

        assertTrue(received.isFailure)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun visibilityUsesToBeforeCcAndKeepsPrivateRecipients() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        listOf(PUBLIC to "", "local" to PUBLIC, "$ACTOR/followers" to "", "local" to "").forEach { (to, cc) ->
            receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1", to = to, cc = cc))
        }
        assertEquals(NoteVisibility.entries.toList(), store.saved.map { it.visibility })
        assertEquals(listOf("local"), store.saved.last().to)
    }

    @Test
    fun attachmentsAcceptStringOrLinkUrlsAndSkipNonHttp() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val attachments = """[
            {"type":"Document","mediaType":"image/png","url":"https://remote.example/a.png","name":"説明"},
            {"type":"Image","url":{"type":"Link","href":"https://remote.example/b.jpg","mediaType":"image/jpeg"}},
            {"type":"Image","url":[{"type":"Link","href":"ftp://remote.example/c.jpg"},"https://remote.example/c.jpg"]},
            {"type":"Document","mediaType":"image/png","url":"javascript:alert(1)"},
            {"type":"Document","mediaType":"image/png"}
        ]"""
        receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1", to = PUBLIC, cc = "", attachment = attachments))
        assertEquals(
            listOf(
                ReceivedNoteAttachment(url = "https://remote.example/a.png", mediaType = "image/png", name = "説明"),
                ReceivedNoteAttachment(url = "https://remote.example/b.jpg", mediaType = "image/jpeg", name = null),
                ReceivedNoteAttachment(url = "https://remote.example/c.jpg", mediaType = null, name = null),
            ),
            store.saved.single().attachments,
        )
    }

    @Test
    fun singleAttachmentObjectIsAccepted() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val attachment = """{"type":"Document","mediaType":"image/png","url":"https://remote.example/a.png"}"""
        receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1", to = PUBLIC, cc = "", attachment = attachment))
        assertEquals(listOf("https://remote.example/a.png"), store.saved.single().attachments.map { it.url })
    }

    @Test
    fun customEmojisAreReadFromTagAndSkipNonHttpIcons() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val tag = """[
            {"type":"Emoji","name":":blob:","icon":{"type":"Image","url":"https://remote.example/blob.png"}},
            {"type":"Hashtag","name":"#kotlin"},
            {"type":"Emoji","name":":bad:","icon":{"type":"Image","url":"javascript:alert(1)"}}
        ]"""
        val target =
            """{"type":"Note","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","content":"<p>:blob:</p>","to":["$PUBLIC"],"tag":$tag}"""
        receive(handler, target)
        assertEquals(
            listOf(CustomEmoji(name = ":blob:", imageUrl = "https://remote.example/blob.png")),
            store.saved.single().emojis,
        )
    }

    @Test
    fun mentionsAreReadFromTag() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val tag = """[
            {"type":"Mention","href":"https://local.example/users/alice","name":"@alice@local.example"},
            {"type":"Hashtag","name":"#kotlin"},
            {"type":"Mention","href":"https://local.example/users/alice","name":"@alice"}
        ]"""
        val target =
            """{"type":"Note","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","content":"<p>@alice</p>","to":["$PUBLIC"],"tag":$tag}"""
        receive(handler, target)
        assertEquals(listOf("https://local.example/users/alice"), store.saved.single().mentions)
    }

    @Test
    fun singleMentionTagObjectIsRead() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val tag = """{"type":"Mention","href":"https://local.example/users/alice"}"""
        val target =
            """{"type":"Note","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","content":"<p>@alice</p>","to":["$PUBLIC"],"tag":$tag}"""
        receive(handler, target)
        assertEquals(listOf("https://local.example/users/alice"), store.saved.single().mentions)
    }

    @Test
    fun quoteIsReadFromEachImplementationsKey() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        listOf(
            """"quote":"https://other.example/notes/fep"""",
            """"quoteUrl":"https://other.example/notes/url"""",
            """"quoteUri":"https://other.example/notes/uri"""",
            """"_misskey_quote":"https://other.example/notes/misskey"""",
            """"quote":{"type":"Link","href":"https://other.example/notes/link"}""",
            """"quote":"https://other.example/notes/fep","_misskey_quote":"https://other.example/notes/misskey"""",
            """"inReplyTo":null""",
        ).forEach { quote ->
            receive(
                handler,
                """{"type":"Note","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","content":"<p>q</p>","to":["$PUBLIC"],$quote}""",
            )
        }
        assertEquals(
            listOf(
                "https://other.example/notes/fep",
                "https://other.example/notes/url",
                "https://other.example/notes/uri",
                "https://other.example/notes/misskey",
                "https://other.example/notes/link",
                "https://other.example/notes/fep",
                null,
            ),
            store.saved.map { it.quote },
        )
    }

    @Test
    fun languageIsReadFromFirstContentMapKey() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        listOf(
            """"content":"<p>q</p>","contentMap":{"ja":"<p>q</p>"}""",
            """"content":"<p>q</p>","contentMap":{"zh-tw":"<p>q</p>","en":"<p>q</p>"}""",
            """"content":"<p>q</p>","contentMap":{"en":"<p>q</p>","ja":"<p>q</p>"}""",
            """"content":"<p>q</p>","contentMap":{"not a language":"<p>q</p>","ja":"<p>q</p>"}""",
            """"content":"<p>q</p>","contentMap":{"und":"<p>q</p>"}""",
            """"content":"<p>q</p>","contentMap":{}""",
            """"content":"<p>q</p>"""",
        ).forEach { content ->
            receive(
                handler,
                """{"type":"Note","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","to":["$PUBLIC"],$content}""",
            )
        }
        assertEquals(listOf("ja", "zh-TW", "en", null, null, null, null), store.saved.map { it.language?.toLanguageTag() })
    }

    @Test
    fun contentPrefersContentOverContentMap() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        listOf(
            """"content":"<p>content</p>","contentMap":{"ja":"<p>map</p>"}""",
            """"contentMap":{"ja":"<p>first</p>","en":"<p>second</p>"}""",
        ).forEach { content ->
            receive(
                handler,
                """{"type":"Note","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","to":["$PUBLIC"],$content}""",
            )
        }
        assertEquals(listOf("<p>content</p>", "<p>first</p>"), store.saved.map { it.content })
    }

    @Test
    fun linkTakesAuthorAndAddressingFromCreate() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val raw = AppJson.parseToJsonElement(
            """{"id":"https://remote.example/activities/1","type":"Create","actor":"$ACTOR","to":["$PUBLIC"],"cc":[],
                "object":{"type":"Link","href":"https://example.com/"}}""",
        ).jsonObject
        handler.handle(InboxRecipient.Shared, ACTOR, AppJson.decodeFromJsonElement(InboxActivity.serializer(), raw), raw)
        val saved = store.saved.single()
        assertEquals("https://example.com/", saved.linkHref)
        assertEquals("https://remote.example/activities/1", saved.id)
        assertEquals(ACTOR, saved.attributedTo)
        assertEquals(NoteVisibility.Public, saved.visibility)
        assertEquals("", saved.content)
    }

    @Test
    fun linkTakesAuthorFromEmbeddedActor() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val raw = AppJson.parseToJsonElement(
            """{"id":"https://remote.example/activities/1","type":"Create","actor":{"id":"$ACTOR","type":"Person"},
                "object":{"type":"Link","href":"https://example.com/"}}""",
        ).jsonObject
        handler.handle(InboxRecipient.Shared, ACTOR, AppJson.decodeFromJsonElement(InboxActivity.serializer(), raw), raw)
        assertEquals(ACTOR, store.saved.single().attributedTo)
    }

    @Test
    fun linkWithoutHttpHrefIsIgnored() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        receive(handler, """{"type":"Link","id":"https://remote.example/links/1","attributedTo":"$ACTOR","href":"javascript:alert(1)"}""")
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun unknownObjectTypeIsRecordedInsteadOfSaved() = runBlocking {
        val store = RecordingNotes()
        val unknownTypes = RecordingUnknownTypes()
        val handler = CreateNoteHandler(store, RecordingVotes(), unknownTypes, NOT_FOUND_ORIGIN)
        val raw = AppJson.parseToJsonElement(
            """{"id":"https://remote.example/activities/1","type":"Create","actor":"$ACTOR","to":["$PUBLIC"],
                "object":{"type":"Article","name":"題名","content":"<p>本文</p>"}}""",
        ).jsonObject
        handler.handle(InboxRecipient.Shared, ACTOR, AppJson.decodeFromJsonElement(InboxActivity.serializer(), raw), raw)
        assertTrue(store.saved.isEmpty())
        assertEquals(listOf(listOf("Article") to "https://remote.example/activities/1"), unknownTypes.recorded)
    }

    @Test
    fun unknownAttachmentAndTagTypesAreRecorded() = runBlocking {
        val store = RecordingNotes()
        val unknownTypes = RecordingUnknownTypes()
        val handler = CreateNoteHandler(store, RecordingVotes(), unknownTypes, NOT_FOUND_ORIGIN)
        val attachments = """[
            {"type":"Document","mediaType":"image/png","url":"https://remote.example/a.png"},
            {"type":"PropertyValue","name":"a","value":"b"},
            {"type":"PropertyValue","name":"c","value":"d"},
            {"type":["Document","https://example.org/CustomMedia"],"url":"https://remote.example/b.png"}
        ]"""
        receive(handler, note(author = ACTOR, id = "https://remote.example/notes/1", to = PUBLIC, cc = "", attachment = attachments))
        val types = listOf("PropertyValue", "https://example.org/CustomMedia")
        assertEquals(types, store.saved.single().unknownTypes)
        assertEquals(listOf(types to "https://remote.example/notes/1"), unknownTypes.recorded)
    }

    @Test
    fun actorObjectIsNotSavedAsPost() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        receive(handler, """{"type":"Person","id":"https://remote.example/people/1","attributedTo":"$ACTOR","content":"<p>hi</p>"}""")
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun questionIsSavedWithPoll() = runBlocking {
        val store = RecordingNotes()
        val handler = CreateNoteHandler(store, RecordingVotes(), UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        val options = """[
            {"type":"Note","name":"犬","replies":{"type":"Collection","totalItems":3}},
            {"type":"Note","name":"猫"},
            {"type":"Note"}
        ]"""
        receive(
            handler,
            """{"type":"Question","id":"https://remote.example/notes/1","attributedTo":"$ACTOR","content":"<p>どっち</p>","to":["$PUBLIC"],"oneOf":$options,"endTime":"2026-01-02T00:00:00Z","votersCount":3}""",
        )
        receive(
            handler,
            """{"type":"Question","id":"https://remote.example/notes/2","attributedTo":"$ACTOR","content":"<p>どれも</p>","to":["$PUBLIC"],"anyOf":$options}""",
        )
        receive(handler, note(author = ACTOR, id = "https://remote.example/notes/3", to = PUBLIC, cc = ""))
        assertEquals(
            listOf(
                ReceivedPoll(
                    options = listOf(ReceivedPollOption(name = "犬", votesCount = 3), ReceivedPollOption(name = "猫", votesCount = null)),
                    multipleChoice = false,
                    endsAt = Instant.parse("2026-01-02T00:00:00Z"),
                    votersCount = 3,
                ),
                ReceivedPoll(
                    options = listOf(ReceivedPollOption(name = "犬", votesCount = 3), ReceivedPollOption(name = "猫", votesCount = null)),
                    multipleChoice = true,
                    endsAt = null,
                    votersCount = null,
                ),
                null,
            ),
            store.saved.map { it.poll },
        )
    }

    @Test
    fun noteWithoutContentAndWithNameIsVote() = runBlocking {
        val store = RecordingNotes()
        val votes = RecordingVotes()
        val handler = CreateNoteHandler(store, votes, UnknownTypeStore { _, _ -> }, NOT_FOUND_ORIGIN)
        receive(handler, vote(author = ACTOR, id = "https://remote.example/votes/1"))
        receive(handler, vote(author = "https://other.example/actor", id = "https://remote.example/votes/2"))
        receive(handler, vote(author = ACTOR, id = "https://other.example/votes/3"))
        assertTrue(store.saved.isEmpty())
        receive(
            handler,
            """{"type":"Note","id":"https://remote.example/notes/4","attributedTo":"$ACTOR","name":"題","inReplyTo":"https://local.example/notes/1","contentMap":{"ja":"<p>返信</p>"}}""",
        )
        assertEquals(listOf("<p>返信</p>"), store.saved.map { it.content })
        assertEquals(
            listOf(
                ReceivedPollVote(
                    id = "https://remote.example/votes/1",
                    voterActorId = ACTOR,
                    questionId = "https://local.example/notes/1",
                    choice = "犬",
                ),
            ),
            votes.received,
        )
    }

    private fun vote(author: String, id: String): String =
        """{"type":"Note","id":"$id","attributedTo":"$author","name":"犬","inReplyTo":"https://local.example/notes/1","to":["https://local.example/users/alice"]}"""

    private suspend fun receive(handler: CreateNoteHandler, target: String) {
        val raw = AppJson.parseToJsonElement("""{"type":"Create","actor":"$ACTOR","object":$target}""").jsonObject
        handler.handle(InboxRecipient.Shared, ACTOR, AppJson.decodeFromJsonElement(InboxActivity.serializer(), raw), raw)
    }

    private fun note(author: String, id: String, to: String, cc: String): String = note(author, id, to, cc, attachment = "[]")

    private fun note(author: String, id: String, to: String, cc: String, attachment: String): String =
        """{"type":"Note","id":"$id","attributedTo":"$author","content":"<p>hello</p>","to":["$to"],"cc":["$cc"],"attachment":$attachment}"""

    private fun originAuthoredBy(author: String): RemoteNotes = RemoteNotes { noteId ->
        RemoteNoteLookup.Found(
            ReceivedNoteJson.read(AppJson.parseToJsonElement(note(author = author, id = noteId, to = PUBLIC, cc = "")).jsonObject)
                ?: error("テストの投稿を読めない"),
        )
    }

    /**
     * @param authorsAtWrite 書き込む時点の保存済みの投稿者。判断した後に別の書き込みが割り込んだ状態を作る
     */
    private class RecordingNotes(
        private val savedAuthors: Map<String, String> = mapOf(),
        private val authorsAtWrite: Map<String, String> = savedAuthors,
    ) : RemoteNoteStore {
        val saved = mutableListOf<ReceivedNote>()

        override suspend fun findAttributedTo(noteId: String): String? = savedAuthors[noteId]

        override suspend fun save(note: ReceivedNote, expectedAttributedTo: String?): Boolean {
            if (authorsAtWrite[note.id] != expectedAttributedTo) return false
            saved.add(note)
            return true
        }

        override suspend fun update(note: ReceivedNote, expectedAttributedTo: String?): Boolean = true

        override suspend fun delete(actorId: String, objectId: String, expectedAttributedTo: String?): Boolean = true
    }

    private class RecordingVotes : PollVoteStore {
        val received = mutableListOf<ReceivedPollVote>()

        override suspend fun vote(vote: ReceivedPollVote) {
            received.add(vote)
        }
    }

    private class RecordingUnknownTypes : UnknownTypeStore {
        val recorded = mutableListOf<Pair<List<String>, String>>()

        override suspend fun record(types: List<String>, sourceUrl: String) {
            recorded.add(types to sourceUrl)
        }
    }

    private companion object {
        const val ACTOR = "https://remote.example/actor"
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
        val NOT_FOUND_ORIGIN = RemoteNotes { noteId -> RemoteNoteLookup.NotFound(RemoteFetchFailure.InvalidDocument(noteId, "投稿のあるサーバーに無い")) }
    }
}
