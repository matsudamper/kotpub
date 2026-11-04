package net.matsudamper.kotpub.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.json.AppJson

class ReactionActivitiesTest {
    @Test
    fun favouriteIsLikeWithoutContent() {
        val json = AppJson.encodeToJsonElement(ReactionActivities.favourite("like-1", LOCAL, NOTE)).jsonObject

        assertEquals("Like", json.string("type"))
        assertEquals(NOTE, json.string("object"))
        assertFalse(json.containsKey("content"))
        assertFalse(json.containsKey("_misskey_reaction"))
    }

    @Test
    fun customEmojiStampCarriesEmojiTag() {
        val tag = ReactionActivity.EmojiTag(
            id = "https://local.example/emojis/blobcat",
            name = ":blobcat:",
            icon = ReactionActivity.Icon(url = "https://local.example/blobcat.png", mediaType = "image/png"),
        )
        val json = AppJson.encodeToJsonElement(
            ReactionActivities.stamp("like-2", LOCAL, NOTE, ":blobcat:", tag),
        ).jsonObject

        assertEquals(":blobcat:", json.string("content"))
        assertEquals(":blobcat:", json.string("_misskey_reaction"))
        val emoji = (json["tag"] as JsonArray).single().jsonObject
        assertEquals("Emoji", emoji.string("type"))
        assertEquals("https://local.example/blobcat.png", emoji["icon"]?.jsonObject?.string("url"))
    }

    @Test
    fun undoEmbedsTheOriginalReaction() {
        val reaction = ReactionActivities.stamp("like-3", LOCAL, NOTE, "👍", null)
        val undo = ReactionActivities.undo("undo-1", reaction)

        assertEquals("Undo", undo.type)
        assertEquals(LOCAL, undo.actor)
        assertEquals("like-3", undo.target.id)
        assertEquals(AppJson.encodeToJsonElement(reaction), (undo.target as LinkOrObject.Embedded).json)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private companion object {
        const val LOCAL = "https://local.example/actor"
        const val NOTE = "https://remote.example/notes/1"
    }
}
