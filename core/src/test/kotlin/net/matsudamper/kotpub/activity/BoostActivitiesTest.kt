package net.matsudamper.kotpub.activity

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.matsudamper.kotpub.json.AppJson

class BoostActivitiesTest {
    @Test
    fun announceIsPublicAndPointsToTheNote() {
        val announce = Json.parseToJsonElement(AppJson.encodeToString(AnnounceActivity.serializer(), announce())).jsonObject

        assertEquals("Announce", announce.getValue("type").jsonPrimitive.content)
        assertEquals(ANNOUNCE_ID, announce.getValue("id").jsonPrimitive.content)
        assertEquals(ACTOR, announce.getValue("actor").jsonPrimitive.content)
        assertEquals(NOTE, announce.getValue("object").jsonPrimitive.content)
        assertEquals("2026-11-10T00:00:00Z", announce.getValue("published").jsonPrimitive.content)
        assertEquals(listOf(ActivityStreamsIri.PUBLIC_AUDIENCE), announce.getValue("to").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(AUTHOR, FOLLOWERS), announce.getValue("cc").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun unlistedAnnounceKeepsPublicOutOfTo() {
        val unlisted = BoostActivities.announce(
            id = ANNOUNCE_ID,
            actor = ACTOR,
            objectUri = NOTE,
            objectAuthor = AUTHOR,
            followers = FOLLOWERS,
            published = Instant.parse("2026-11-10T00:00:00Z"),
            audience = BoostAudience.UNLISTED,
        )

        assertEquals(listOf(FOLLOWERS), unlisted.to)
        assertEquals(listOf(ActivityStreamsIri.PUBLIC_AUDIENCE, AUTHOR), unlisted.cc)
    }

    @Test
    fun undoEmbedsTheAnnounceSoTheReceiverCanFindIt() {
        val undo = Json.parseToJsonElement(
            AppJson.encodeToString(OutgoingActivity.serializer(), BoostActivities.undo(id = UNDO_ID, announce = announce())),
        ).jsonObject

        assertEquals("Undo", undo.getValue("type").jsonPrimitive.content)
        assertEquals(UNDO_ID, undo.getValue("id").jsonPrimitive.content)
        assertEquals(ACTOR, undo.getValue("actor").jsonPrimitive.content)
        val embedded = undo.getValue("object").jsonObject
        assertEquals("Announce", embedded.getValue("type").jsonPrimitive.content)
        assertEquals(ANNOUNCE_ID, embedded.getValue("id").jsonPrimitive.content)
        assertEquals(NOTE, embedded.getValue("object").jsonPrimitive.content)
    }

    @Test
    fun undoRejectsTheSameIdAsTheAnnounce() {
        assertFailsWith<IllegalArgumentException> { BoostActivities.undo(id = ANNOUNCE_ID, announce = announce()) }
    }

    private fun announce() = BoostActivities.announce(
        id = ANNOUNCE_ID,
        actor = ACTOR,
        objectUri = NOTE,
        objectAuthor = AUTHOR,
        followers = FOLLOWERS,
        published = Instant.parse("2026-11-10T00:00:00Z"),
        audience = BoostAudience.PUBLIC,
    )

    private companion object {
        const val ACTOR = "https://local.example/users/alice"
        const val FOLLOWERS = "$ACTOR/followers"
        const val AUTHOR = "https://remote.example/users/bob"
        const val NOTE = "https://remote.example/notes/1"
        const val ANNOUNCE_ID = "$ACTOR/announces/1"
        const val UNDO_ID = "$ACTOR/undo/1"
    }
}
