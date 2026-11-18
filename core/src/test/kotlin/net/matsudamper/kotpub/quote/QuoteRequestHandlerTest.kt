package net.matsudamper.kotpub.quote

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
import net.matsudamper.kotpub.actor.RemoteActorLookup
import net.matsudamper.kotpub.actor.RemoteActors
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.inbox.InboxHandling
import net.matsudamper.kotpub.inbox.InboxRecipient
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.note.NoteUrls
import net.matsudamper.kotpub.note.NoteVisibility
import net.matsudamper.kotpub.note.ReceivedNote
import net.matsudamper.kotpub.note.RemoteNoteLookup
import net.matsudamper.kotpub.note.RemoteNotes
import net.matsudamper.kotpub.note.StoredNote

class QuoteRequestHandlerTest {
    private val notes = FakeNoteStore().apply {
        add(
            StoredNote(
                publicId = PublicNoteId(PUBLIC_ID),
                username = TestLocalActor.USERNAME,
                contentHtml = "<p>quoted</p>",
                summary = null,
                publishedAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = null,
                attachments = listOf(),
                inReplyTo = null,
                quote = null,
                mentions = listOf(),
                emojis = listOf(),
            ),
        )
    }
    private val noteUrls = NoteUrls(domain = TestLocalActor.DOMAIN, publicId = PublicNoteId(PUBLIC_ID))

    private fun handler(
        approvals: QuoteApprovals,
        remoteActors: RemoteActors,
        remoteNotes: RemoteNotes,
    ): QuoteRequestHandler = QuoteRequestHandler(
        domain = TestLocalActor.DOMAIN,
        notes = notes,
        remoteActors = remoteActors,
        remoteNotes = remoteNotes,
        approvals = approvals,
    )

    private fun remoteNotesPostedBy(attributedTo: String): RemoteNotes =
        RemoteNotes { noteId ->
            RemoteNoteLookup.Found(
                ReceivedNote(
                    id = noteId,
                    attributedTo = attributedTo,
                    content = "<p>quoting</p>",
                    language = null,
                    published = null,
                    updated = null,
                    to = listOf(),
                    cc = listOf(),
                    inReplyTo = null,
                    quote = noteUrls.noteUrl,
                    summary = null,
                    sensitive = false,
                    visibility = NoteVisibility.Public,
                    attachments = listOf(),
                    emojis = listOf(),
                ),
            )
        }

    private fun remoteActorsFailingWith(failure: RemoteFetchFailure): RemoteActors =
        object : RemoteActors by TestRemoteActor.remoteActors() {
            override suspend fun findActor(actorId: String): RemoteActorLookup = RemoteActorLookup.Unavailable(failure)
        }

    private suspend fun handle(
        approvals: QuoteApprovals,
        json: String,
        remoteActors: RemoteActors = TestRemoteActor.remoteActors(),
        remoteNotes: RemoteNotes = remoteNotesPostedBy(TestRemoteActor.ACTOR_ID),
    ): InboxHandling {
        val rawActivityJson = AppJson.parseToJsonElement(json) as JsonObject
        return handler(approvals, remoteActors, remoteNotes).handle(
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
        assertTrue(accept.result.orEmpty().startsWith("${noteUrls.noteUrl}/quote_authorizations/"))
    }

    @Test
    fun `引用した投稿の id だけが入っていても承認する`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        assertEquals(InboxHandling.Handled, handle(approvals, quoteRequestJson(instrument = "\"$QUOTING_NOTE\"")))
    }

    @Test
    fun `取得した引用元の投稿者が署名した相手と違えば、埋め込みで名乗っていても承認しない`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        val handling = handle(
            approvals,
            quoteRequestJson(),
            remoteNotes = remoteNotesPostedBy("https://remote.example/users/bob"),
        )

        assertIs<InboxHandling.Ignored>(handling)
        assertTrue(approvals.enqueued.isEmpty())
    }

    @Test
    fun `引用元の投稿を取得できなければ承認しない`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        val handling = handle(
            approvals,
            quoteRequestJson(),
            remoteNotes = { RemoteNoteLookup.NotFound(RemoteFetchFailure.UnsuccessfulStatus(QUOTING_NOTE, HttpStatusCodes.NOT_FOUND)) },
        )

        assertIs<InboxHandling.Ignored>(handling)
        assertTrue(approvals.enqueued.isEmpty())
    }

    @Test
    fun `引用元の投稿を一時的に取得できなければ送り直してもらう`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            handle(
                FakeQuoteApprovals(),
                quoteRequestJson(),
                remoteNotes = { RemoteNoteLookup.Unavailable(RemoteFetchFailure.Throttled(QUOTING_NOTE)) },
            )
        }
    }

    @Test
    fun `引用した相手のアクター文書を一時的に取得できなければ送り直してもらう`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        assertFailsWith<IllegalStateException> {
            handle(approvals, quoteRequestJson(), remoteActorsFailingWith(RemoteFetchFailure.Throttled(TestRemoteActor.ACTOR_ID)))
        }
        assertTrue(approvals.enqueued.isEmpty())
    }

    @Test
    fun `引用した相手のサーバーが 429 や 5xx を返したら送り直してもらう`() = runBlocking {
        listOf(HttpStatusCodes.TOO_MANY_REQUESTS, HttpStatusCodes.SERVICE_UNAVAILABLE).forEach { status ->
            assertFailsWith<IllegalStateException> {
                handle(
                    FakeQuoteApprovals(),
                    quoteRequestJson(),
                    remoteActorsFailingWith(RemoteFetchFailure.UnsuccessfulStatus(TestRemoteActor.ACTOR_ID, status)),
                )
            }
        }
    }

    @Test
    fun `引用した相手のアクター文書を取得できない状態が続くものなら送り直させない`() = runBlocking {
        val approvals = FakeQuoteApprovals()

        val handling = handle(
            approvals,
            quoteRequestJson(),
            remoteActorsFailingWith(RemoteFetchFailure.UnsuccessfulStatus(TestRemoteActor.ACTOR_ID, HttpStatusCodes.NOT_FOUND)),
        )

        assertIs<InboxHandling.Ignored>(handling)
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
    fun `Accept の result の URL から、引用の組を示す QuoteAuthorization を返す`() = runBlocking {
        val approvals = FakeQuoteApprovals()
        val authorizationUrl = acceptedAuthorizationUrl(approvals)

        val response = getAuthorization(approvals, authorizationUrl.substringAfterLast('/'))

        assertEquals(HttpStatusCodes.OK, response.status)
        val json = AppJson.parseToJsonElement(response.body.decodeToString()).jsonObject
        assertEquals(QuoteAuthorization.TYPE, json.getValue("type").jsonPrimitive.content)
        assertEquals(authorizationUrl, json.getValue("id").jsonPrimitive.content)
        assertEquals(TestLocalActor.urls.actorId, json.getValue("attributedTo").jsonPrimitive.content)
        assertEquals(QUOTING_NOTE, json.getValue("interactingObject").jsonPrimitive.content)
        assertEquals(noteUrls.noteUrl, json.getValue("interactionTarget").jsonPrimitive.content)
    }

    @Test
    fun `書き換えた URL には承認を返さない`() = runBlocking {
        val approvals = FakeQuoteApprovals()
        val (quoter, _, signature) = acceptedAuthorizationUrl(approvals).substringAfterLast('/').split('.')
        val otherNote = Base64.getUrlEncoder().withoutPadding().encodeToString("https://remote.example/notes/other".toByteArray())

        val response = getAuthorization(approvals, "$quoter.$otherNote.$signature")

        assertEquals(HttpStatusCodes.NOT_FOUND, response.status)
    }

    @Test
    fun `承認した後にブロックした相手には承認を返さない`() = runBlocking {
        val token = acceptedAuthorizationUrl(FakeQuoteApprovals()).substringAfterLast('/')

        val response = getAuthorization(FakeQuoteApprovals(refusedActorIds = setOf(TestRemoteActor.ACTOR_ID)), token)

        assertEquals(HttpStatusCodes.NOT_FOUND, response.status)
    }

    @Test
    fun `投稿が消えたら承認も返さない`() = runBlocking {
        val approvals = FakeQuoteApprovals()
        val token = acceptedAuthorizationUrl(approvals).substringAfterLast('/')
        notes.delete(PublicNoteId(PUBLIC_ID))

        assertEquals(HttpStatusCodes.NOT_FOUND, getAuthorization(approvals, token).status)
    }

    private suspend fun acceptedAuthorizationUrl(approvals: FakeQuoteApprovals): String {
        handle(approvals, quoteRequestJson())
        val accept = AppJson.decodeFromString(OutgoingActivity.serializer(), approvals.enqueued.single().acceptBody)
        return checkNotNull(accept.result)
    }

    private suspend fun getAuthorization(approvals: QuoteApprovals, token: String): EndpointResponse =
        QuoteAuthorizationEndpoint(domain = TestLocalActor.DOMAIN, notes = notes, approvals = approvals)
            .get(publicId = PUBLIC_ID, token = token, accept = null)

    private companion object {
        const val PUBLIC_ID = "quoted1"
        const val QUOTING_NOTE = "https://remote.example/users/alice/statuses/1"
    }
}
