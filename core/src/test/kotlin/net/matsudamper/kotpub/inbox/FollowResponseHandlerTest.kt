package net.matsudamper.kotpub.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activity.FollowActivities
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activity.OutgoingActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.follower.FollowingResponses
import net.matsudamper.kotpub.json.AppJson

class FollowResponseHandlerTest {
    @Test
    fun followAndUndoPreserveTheOriginalActivity() {
        val follow = FollowActivities.follow("follow-1", LOCAL, REMOTE)
        val undo = FollowActivities.undo("undo-1", follow)
        assertEquals("Follow", follow.type)
        assertEquals(REMOTE, follow.target.id)
        assertEquals("Undo", undo.type)
        assertEquals("follow-1", undo.target.id)
        val embedded = undo.target as LinkOrObject.Embedded
        assertEquals(AppJson.encodeToJsonElement(follow), embedded.json)
    }

    @Test
    fun responsesPassOriginalIdAndRejectMismatchedActorsOrObjects() = runBlocking {
        val received = mutableListOf<String>()
        val responses = object : FollowingResponses {
            override fun accept(actorId: String, followActivityId: String) {
                received.add("accept:$followActivityId")
            }
            override fun reject(actorId: String, followActivityId: String) {
                received.add("reject:$followActivityId")
            }
        }
        val follow = FollowActivities.follow("follow-1", LOCAL, REMOTE)
        val embedded = FollowActivities.undo("unused", follow).target
        listOf("Accept", "Reject").forEach { type ->
            val handler = FollowResponseHandler(type, responses)
            val activity = InboxActivity(type = type, actor = LinkOrObject.Link(REMOTE), target = embedded)
            handler.handle(InboxRecipient.Shared, REMOTE, activity, JsonObject(mapOf()))
            handler.handle(InboxRecipient.Shared, LOCAL, activity, JsonObject(mapOf()))
            val wrong = OutgoingActivity(id = "wrong", type = "Like", actor = LOCAL, target = LinkOrObject.Link(REMOTE))
            handler.handle(InboxRecipient.Shared, REMOTE, activity.copy(target = LinkOrObject.Embedded(AppJson.encodeToJsonElement(wrong).jsonObject)), JsonObject(mapOf()))
        }
        assertEquals(listOf("accept:follow-1", "reject:follow-1"), received)
    }

    private companion object {
        const val LOCAL = "https://local.example/actor"
        const val REMOTE = "https://remote.example/actor"
    }
}
