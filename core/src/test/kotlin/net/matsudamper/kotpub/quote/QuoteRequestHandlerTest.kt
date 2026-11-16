package net.matsudamper.kotpub.quote

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.matsudamper.kotpub.FakeNoteStore
import net.matsudamper.kotpub.FakeQuoteApprovals
import net.matsudamper.kotpub.TestLocalActor
import net.matsudamper.kotpub.TestRemoteActor
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activity.OutgoingActivity
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.inbox.InboxHandling
import net.matsudamper.kotpub.inbox.InboxRecipient
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.note.NoteUrls
import net.matsudamper.kotpub.note.StoredNote

class QuoteRequestHandlerTest {
    private val notes = FakeNoteStore().apply {
        add(
            StoredNote(
                publicId = PublicNoteId(PUBLIC_ID),
                username = TestLocalActor.USERNAME,
                contentHtml = "<p>quoted</p>",
                publishedAt = Instant.parse("2026-01-01T00:00:00Z"),
                attachments = listOf(),
                inReplyTo = null,
                quote = null,
                mentions = listOf(),
                emojis = listOf(),
            ),
        )
    }
    private val noteUrls = NoteUrls(domain = TestLocalActor.DOMAIN, publicId = PublicNoteId(PUBLIC_ID))

    private fun handler(approvals: QuoteApprovals): QuoteRequestHandler = QuoteRequestHandler(
        domain = TestLocalActor.DOMAIN,
        notes = notes,
        remoteActors = TestRemoteActor.remoteActors(),
        approvals = approvals,
    )

    private suspend fun handle(approvals: QuoteApprovals, json: String): InboxHandling {
        val rawActivityJson = AppJson.parseToJsonElement(json) as JsonObject
        return handler(approvals).handle(
            recipient = InboxRecipient.Account(TestLocalActor.urls),
            verifiedSignerActorId = TestRemoteActor.ACTOR_ID,
            activity = AppJson.decodeFromJsonElement(InboxActivity.serializer(), rawActivityJson),
            rawActivityJson = rawActivityJson,
        )
    }

    private fun quoteRequestJson(
        target: String = noteUrls.noteUrl,
        instrument: String = """{"id":"$QUOTING_NOTE","type":"Note","attributedTo":"${TestRemoteActor.ACTOR_ID}"}""",
    ): String =
        """{"id":"$QUOTING_NOTE#quote_request","type":"QuoteRequest","actor":"${TestRemoteActor.ACTOR_ID}","object":"$target","instrument":$instrument}"""

    @Test
    fun `こちらの投稿への引用は承認して、承認の id を result に入れた Accept を登録する`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        assertEquals(InboxHandling.Handled, handle(approvals, quoteRequestJson()))

        val enqueued = approvals.enqueued.single()
        assertEquals(TestLocalActor.USERNAME, enqueued.username)
        assertEquals(TestRemoteActor.INBOX, enqueued.inbox)
        val accept = AppJson.decodeFromString(OutgoingActivity.serializer(), enqueued.acceptBody)
        assertEquals(OutgoingActivity.TYPE_ACCEPT, accept.type)
        assertEquals(TestLocalActor.urls.actorId, accept.actor)
        assertEquals(noteUrls.quoteAuthorizationUrl(QUOTING_NOTE), accept.result)
    }

    @Test
    fun `引用した投稿の id だけが入っていても承認する`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        assertEquals(InboxHandling.Handled, handle(approvals, quoteRequestJson(instrument = "\"$QUOTING_NOTE\"")))
    }

    @Test
    fun `署名した相手と別のホストの投稿を引用元にした要求は承認しない`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        val handling = handle(approvals, quoteRequestJson(instrument = "\"https://other.example/notes/1\""))

        assertIs<InboxHandling.Ignored>(handling)
        assertTrue(approvals.enqueued.isEmpty())
    }

    @Test
    fun `署名した相手と別のアクターの投稿を引用元にした要求は承認しない`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        val handling = handle(
            approvals,
            quoteRequestJson(instrument = """{"id":"$QUOTING_NOTE","attributedTo":"https://remote.example/users/bob"}"""),
        )

        assertIs<InboxHandling.Ignored>(handling)
        assertTrue(approvals.enqueued.isEmpty())
    }

    @Test
    fun `無い投稿への引用は承認しない`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        val handling = handle(approvals, quoteRequestJson(target = "https://${TestLocalActor.DOMAIN}/notes/missing"))

        assertIs<InboxHandling.Ignored>(handling)
        assertTrue(approvals.enqueued.isEmpty())
    }

    @Test
    fun `受け付けない相手には承認を返さない`() = runBlocking {
        val approvals = FakeQuoteApprovals(refusedActorIds = setOf(TestRemoteActor.ACTOR_ID))

        assertIs<InboxHandling.Ignored>(handle(approvals, quoteRequestJson()))
    }

    @Test
    fun `承認の id から、引用の組を示す QuoteAuthorization を返す`() = runBlocking {
        val authorizationUrl = noteUrls.quoteAuthorizationUrl(QUOTING_NOTE)
        val token = authorizationUrl.substringAfterLast('/')

        val response = QuoteAuthorizationEndpoint(domain = TestLocalActor.DOMAIN, notes = notes)
            .get(publicId = PUBLIC_ID, token = token, accept = null)

        assertEquals(HttpStatusCodes.OK, response.status)
        val json = AppJson.parseToJsonElement(response.body.decodeToString()).jsonObject
        assertEquals(QuoteAuthorization.TYPE, json.getValue("type").jsonPrimitive.content)
        assertEquals(authorizationUrl, json.getValue("id").jsonPrimitive.content)
        assertEquals(TestLocalActor.urls.actorId, json.getValue("attributedTo").jsonPrimitive.content)
        assertEquals(QUOTING_NOTE, json.getValue("interactingObject").jsonPrimitive.content)
        assertEquals(noteUrls.noteUrl, json.getValue("interactionTarget").jsonPrimitive.content)
    }

    @Test
    fun `投稿が消えたら承認も返さない`() = runBlocking {
        val token = noteUrls.quoteAuthorizationUrl(QUOTING_NOTE).substringAfterLast('/')
        notes.delete(PublicNoteId(PUBLIC_ID))

        val response = QuoteAuthorizationEndpoint(domain = TestLocalActor.DOMAIN, notes = notes)
            .get(publicId = PUBLIC_ID, token = token, accept = null)

        assertEquals(HttpStatusCodes.NOT_FOUND, response.status)
    }

    private companion object {
        const val PUBLIC_ID = "quoted1"
        const val QUOTING_NOTE = "https://remote.example/users/alice/statuses/1"
    }
}
