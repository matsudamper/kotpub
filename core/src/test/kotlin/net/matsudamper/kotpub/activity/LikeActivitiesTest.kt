package net.matsudamper.kotpub.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.matsudamper.kotpub.emoji.EmojiObject
import net.matsudamper.kotpub.json.AppJson

class LikeActivitiesTest {
    @Test
    fun likePointsToTheNote() {
        val like = encode(LikeActivities.like(id = LIKE_ID, actor = ACTOR, objectUri = NOTE))

        assertEquals("Like", like.getValue("type").jsonPrimitive.content)
        assertEquals(LIKE_ID, like.getValue("id").jsonPrimitive.content)
        assertEquals(ACTOR, like.getValue("actor").jsonPrimitive.content)
        assertEquals(NOTE, like.getValue("object").jsonPrimitive.content)
    }

    @Test
    fun undoEmbedsTheLikeSoTheReceiverCanFindIt() {
        val like = LikeActivities.like(id = LIKE_ID, actor = ACTOR, objectUri = NOTE)
        val undo = encode(LikeActivities.undo(id = UNDO_ID, like = like))

        assertEquals("Undo", undo.getValue("type").jsonPrimitive.content)
        assertEquals(UNDO_ID, undo.getValue("id").jsonPrimitive.content)
        assertEquals(ACTOR, undo.getValue("actor").jsonPrimitive.content)
        val embedded = undo.getValue("object").jsonObject
        assertEquals("Like", embedded.getValue("type").jsonPrimitive.content)
        assertEquals(LIKE_ID, embedded.getValue("id").jsonPrimitive.content)
        assertEquals(ACTOR, embedded.getValue("actor").jsonPrimitive.content)
        assertEquals(NOTE, embedded.getValue("object").jsonPrimitive.content)
    }

    @Test
    fun unicodeReactionCarriesTheEmojiWithoutTag() {
        val reaction = encode(
            LikeActivities.reaction(id = LIKE_ID, actor = ACTOR, objectUri = NOTE, emoji = ReactionEmoji.Unicode("👍")),
        )

        assertEquals("Like", reaction.getValue("type").jsonPrimitive.content)
        assertEquals(NOTE, reaction.getValue("object").jsonPrimitive.content)
        assertEquals("👍", reaction.getValue("content").jsonPrimitive.content)
        assertEquals("👍", reaction.getValue("_misskey_reaction").jsonPrimitive.content)
        assertNull(reaction["tag"])
    }

    @Test
    fun customReactionCarriesTheEmojiImageInTag() {
        val emoji = EmojiObject(
            id = "https://local.example/emojis/kotlin",
            name = ":kotlin:",
            icon = EmojiObject.Icon(url = "https://local.example/custom-emojis/abc", mediaType = "image/png"),
        )
        val reaction = encode(
            LikeActivities.reaction(id = LIKE_ID, actor = ACTOR, objectUri = NOTE, emoji = ReactionEmoji.Custom(emoji)),
        )

        assertEquals(":kotlin:", reaction.getValue("content").jsonPrimitive.content)
        assertEquals(":kotlin:", reaction.getValue("_misskey_reaction").jsonPrimitive.content)
        val tag = reaction.getValue("tag").jsonArray.single().jsonObject
        assertEquals("Emoji", tag.getValue("type").jsonPrimitive.content)
        assertEquals("https://local.example/emojis/kotlin", tag.getValue("id").jsonPrimitive.content)
        assertEquals(":kotlin:", tag.getValue("name").jsonPrimitive.content)
        val icon = tag.getValue("icon").jsonObject
        assertEquals("Image", icon.getValue("type").jsonPrimitive.content)
        assertEquals("https://local.example/custom-emojis/abc", icon.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun undoEmbedsTheReactionAsSent() {
        val reaction = LikeActivities.reaction(id = LIKE_ID, actor = ACTOR, objectUri = NOTE, emoji = ReactionEmoji.Unicode("👍"))
        val undo = encode(LikeActivities.undo(id = UNDO_ID, like = reaction))

        val embedded = undo.getValue("object").jsonObject
        assertEquals(LIKE_ID, embedded.getValue("id").jsonPrimitive.content)
        assertEquals("👍", embedded.getValue("_misskey_reaction").jsonPrimitive.content)
    }

    @Test
    fun undoRejectsOtherActivities() {
        val follow = FollowActivities.follow(id = "$ACTOR/follows/1", actor = ACTOR, target = "https://remote.example/users/bob")

        assertFailsWith<IllegalArgumentException> { LikeActivities.undo(id = UNDO_ID, like = follow) }
    }

    @Test
    fun undoRejectsTheSameIdAsTheLike() {
        val like = LikeActivities.like(id = LIKE_ID, actor = ACTOR, objectUri = NOTE)

        assertFailsWith<IllegalArgumentException> { LikeActivities.undo(id = LIKE_ID, like = like) }
    }

    private fun encode(activity: OutgoingActivity) =
        Json.parseToJsonElement(AppJson.encodeToString(OutgoingActivity.serializer(), activity)).jsonObject

    private companion object {
        const val ACTOR = "https://local.example/users/alice"
        const val NOTE = "https://remote.example/notes/1"
        const val LIKE_ID = "$ACTOR/likes/1"
        const val UNDO_ID = "$ACTOR/undo/1"
    }
}
