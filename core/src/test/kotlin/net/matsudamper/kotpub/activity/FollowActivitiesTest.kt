package net.matsudamper.kotpub.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.matsudamper.kotpub.json.AppJson

class FollowActivitiesTest {
    @Test
    fun rejectEmbedsTheFollowFromTheFollower() {
        val follow = FollowActivities.follow(id = FOLLOW_ID, actor = FOLLOWER, target = FOLLOWEE)
        val reject = AppJson.encodeToJsonElement(
            OutgoingActivity.serializer(),
            FollowActivities.reject(id = REJECT_ID, actor = FOLLOWEE, follow = follow),
        ).jsonObject

        assertEquals("Reject", reject.getValue("type").jsonPrimitive.content)
        assertEquals(REJECT_ID, reject.getValue("id").jsonPrimitive.content)
        assertEquals(FOLLOWEE, reject.getValue("actor").jsonPrimitive.content)
        val embedded = reject.getValue("object").jsonObject
        assertEquals("Follow", embedded.getValue("type").jsonPrimitive.content)
        assertEquals(FOLLOW_ID, embedded.getValue("id").jsonPrimitive.content)
        assertEquals(FOLLOWER, embedded.getValue("actor").jsonPrimitive.content)
        assertEquals(FOLLOWEE, embedded.getValue("object").jsonPrimitive.content)
    }

    @Test
    fun rejectRejectsOtherThanFollow() {
        val block = BlockActivities.block(id = FOLLOW_ID, actor = FOLLOWER, target = FOLLOWEE)
        assertFailsWith<IllegalArgumentException> { FollowActivities.reject(id = REJECT_ID, actor = FOLLOWEE, follow = block) }
    }

    private companion object {
        const val FOLLOWER = "https://remote.example/users/bob"
        const val FOLLOWEE = "https://local.example/users/alice"
        const val FOLLOW_ID = "https://remote.example/follows/1"
        const val REJECT_ID = "https://local.example/users/alice#rejects/follows/1"
    }
}
