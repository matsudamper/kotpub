package net.matsudamper.kotpub.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.FakeBoostStore
import net.matsudamper.kotpub.TestLocalActor
import net.matsudamper.kotpub.TestRemoteActor
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.note.NoteVisibility
import net.matsudamper.kotpub.note.ReceivedNote
import net.matsudamper.kotpub.note.RemoteNotes

class AnnounceHandlerTest {
    private val localNoteUrl = "https://${TestLocalActor.DOMAIN}/notes/note1"

    private val remoteNoteId = "https://other.example/notes/1"

    private val activityUri = "${TestRemoteActor.ACTOR_ID}/statuses/1/activity"

    private fun remoteNote(visibility: NoteVisibility): ReceivedNote =
        ReceivedNote(
            id = remoteNoteId,
            attributedTo = "https://other.example/users/bob",
            content = "<p>hello</p>",
            published = null,
            to = listOf(),
            cc = listOf(),
            inReplyTo = null,
            summary = null,
            sensitive = false,
            visibility = visibility,
            attachments = listOf(),
        )

    private suspend fun handle(
        json: String,
        boosts: FakeBoostStore,
        notes: Map<String, ReceivedNote> = mapOf(),
    ) {
        val rawActivityJson = AppJson.parseToJsonElement(json) as JsonObject
        AnnounceHandler(
            domain = TestLocalActor.DOMAIN,
            remoteActors = TestRemoteActor.remoteActors(),
            remoteNotes = RemoteNotes { notes[it] },
            boosts = boosts,
        ).handle(
            recipient = InboxRecipient.Shared,
            verifiedSignerActorId = TestRemoteActor.ACTOR_ID,
            activity = AppJson.decodeFromJsonElement(InboxActivity.serializer(), rawActivityJson),
            rawActivityJson = rawActivityJson,
        )
    }

    private fun announce(
        id: String,
        target: String,
        to: String,
        cc: String,
    ): String =
        """
        {"id":"$id","type":"Announce","actor":"${TestRemoteActor.ACTOR_ID}","published":"2026-11-01T00:00:00Z",
         "to":["$to"],"cc":["$cc"],"object":"$target"}
        """.trimIndent()

    @Test
    fun `こちらの投稿のブーストは押した相手と Announce の id を記録する`() = runBlocking {
        val boosts = FakeBoostStore()

        handle(announce(activityUri, localNoteUrl, PUBLIC, "${TestRemoteActor.ACTOR_ID}/followers"), boosts)

        val boost = boosts.rows.single()
        assertEquals(PublicNoteId("note1"), boost.notePublicId)
        assertEquals(TestRemoteActor.ACTOR_ID, boost.actor.actorId)
        assertEquals(activityUri, boost.activityUri)
        assertTrue(boosts.remoteRows.isEmpty())
    }

    @Test
    fun `他のサーバーの投稿のブーストは取得した投稿と一緒に渡す`() = runBlocking {
        val boosts = FakeBoostStore()

        handle(
            announce(activityUri, remoteNoteId, PUBLIC, "${TestRemoteActor.ACTOR_ID}/followers"),
            boosts,
            notes = mapOf(remoteNoteId to remoteNote(NoteVisibility.Public)),
        )

        val boost = boosts.remoteRows.single()
        assertEquals(activityUri, boost.activityUri)
        assertEquals(TestRemoteActor.ACTOR_ID, boost.actorUri)
        assertEquals(NoteVisibility.Public, boost.visibility)
        assertEquals(remoteNoteId, boost.note.id)
        assertEquals("2026-11-01T00:00:00Z", boost.published.toString())
        assertTrue(boosts.rows.isEmpty())
    }

    @Test
    fun `取得できない投稿や公開されていない投稿のブーストは渡さない`() = runBlocking {
        val boosts = FakeBoostStore()

        handle(announce(activityUri, remoteNoteId, PUBLIC, ""), boosts)
        handle(
            announce(activityUri, remoteNoteId, PUBLIC, ""),
            boosts,
            notes = mapOf(remoteNoteId to remoteNote(NoteVisibility.Followers)),
        )

        assertTrue(boosts.remoteRows.isEmpty())
    }

    @Test
    fun `ブーストした相手と別のホストの id を名乗る Announce は受け付けない`() = runBlocking {
        val boosts = FakeBoostStore()

        handle(announce("https://other.example/activities/1", localNoteUrl, PUBLIC, ""), boosts)

        assertTrue(boosts.rows.isEmpty())
    }

    @Test
    fun `actor が署名者と違う Announce は受け付けない`() = runBlocking {
        val boosts = FakeBoostStore()

        handle(
            """
            {"id":"$activityUri","type":"Announce","actor":"https://remote.example/users/mallory",
             "to":["$PUBLIC"],"object":"$localNoteUrl"}
            """.trimIndent(),
            boosts,
        )

        assertTrue(boosts.rows.isEmpty())
    }

    private companion object {
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
    }
}
