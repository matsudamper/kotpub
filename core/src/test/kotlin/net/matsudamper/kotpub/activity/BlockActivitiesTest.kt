package net.matsudamper.kotpub.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.matsudamper.kotpub.json.AppJson

class BlockActivitiesTest {
    @Test
    fun blockPointsToTheBlockedActor() {
        val block = encode(BlockActivities.block(id = BLOCK_ID, actor = ACTOR, target = TARGET))

        assertEquals("Block", block.getValue("type").jsonPrimitive.content)
        assertEquals(BLOCK_ID, block.getValue("id").jsonPrimitive.content)
        assertEquals(ACTOR, block.getValue("actor").jsonPrimitive.content)
        assertEquals(TARGET, block.getValue("object").jsonPrimitive.content)
    }

    @Test
    fun undoEmbedsTheBlockSoTheReceiverCanFindIt() {
        val block = BlockActivities.block(id = BLOCK_ID, actor = ACTOR, target = TARGET)
        val undo = encode(BlockActivities.undo(id = UNDO_ID, block = block))

        assertEquals("Undo", undo.getValue("type").jsonPrimitive.content)
        assertEquals(UNDO_ID, undo.getValue("id").jsonPrimitive.content)
        assertEquals(ACTOR, undo.getValue("actor").jsonPrimitive.content)
        val embedded = undo.getValue("object").jsonObject
        assertEquals("Block", embedded.getValue("type").jsonPrimitive.content)
        assertEquals(BLOCK_ID, embedded.getValue("id").jsonPrimitive.content)
        assertEquals(TARGET, embedded.getValue("object").jsonPrimitive.content)
    }

    @Test
    fun undoRejectsOtherThanBlock() {
        val follow = FollowActivities.follow(id = BLOCK_ID, actor = ACTOR, target = TARGET)
        assertFailsWith<IllegalArgumentException> { BlockActivities.undo(id = UNDO_ID, block = follow) }
    }

    private fun encode(activity: OutgoingActivity): JsonObject =
        AppJson.encodeToJsonElement(OutgoingActivity.serializer(), activity).jsonObject

    private companion object {
        const val ACTOR = "https://local.example/users/alice"
        const val TARGET = "https://remote.example/users/bob"
        const val BLOCK_ID = "https://local.example/users/alice#blocks/1"
        const val UNDO_ID = "https://local.example/users/alice#undo/1"
    }
}
