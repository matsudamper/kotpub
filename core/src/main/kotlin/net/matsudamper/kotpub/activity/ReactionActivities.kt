package net.matsudamper.kotpub.activity

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.json.AppJson

object ReactionActivities {
    fun favourite(id: String, actor: String, note: String): ReactionActivity =
        ReactionActivity(
            id = id,
            actor = actor,
            target = note,
            content = null,
            misskeyReaction = null,
            tag = listOf(),
        )

    /**
     * @param emoji Unicode の絵文字、またはカスタム絵文字の `:name:`
     * @param customEmoji カスタム絵文字のときだけ渡す。`name` は [emoji] と同じにする
     */
    fun stamp(
        id: String,
        actor: String,
        note: String,
        emoji: String,
        customEmoji: ReactionActivity.EmojiTag?,
    ): ReactionActivity {
        require(emoji.isNotBlank())
        require(customEmoji == null || customEmoji.name == emoji)
        return ReactionActivity(
            id = id,
            actor = actor,
            target = note,
            content = emoji,
            misskeyReaction = emoji,
            tag = listOfNotNull(customEmoji),
        )
    }

    fun undo(id: String, reaction: ReactionActivity): OutgoingActivity =
        OutgoingActivity(
            id = id,
            type = "Undo",
            actor = reaction.actor,
            target = LinkOrObject.Embedded(AppJson.encodeToJsonElement(reaction).jsonObject),
        )
}
