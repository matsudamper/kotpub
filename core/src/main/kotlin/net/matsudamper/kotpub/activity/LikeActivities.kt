package net.matsudamper.kotpub.activity

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.emoji.EmojiObject
import net.matsudamper.kotpub.inbox.ReactionHandler
import net.matsudamper.kotpub.json.AppJson

/**
 * こちらのアクターが他のサーバーの投稿に付けるいいねと、その取り消し
 */
object LikeActivities {
    /**
     * @param objectUri いいねする投稿の id
     */
    fun like(id: String, actor: String, objectUri: String): OutgoingActivity =
        OutgoingActivity(id = id, type = ReactionHandler.LIKE_TYPE, actor = actor, target = LinkOrObject.Link(objectUri))

    /**
     * 絵文字を付けた `Like`。Misskey と Pleroma 系はリアクションとして、Mastodon はいいねとして受け取る。
     * 相手はどちらも 1 つの投稿に 1 つしか持たないので、付け替えるときは前の `Like` を [undo] してから送る
     *
     * @param objectUri リアクションする投稿の id
     */
    fun reaction(id: String, actor: String, objectUri: String, emoji: ReactionEmoji): OutgoingActivity =
        like(id = id, actor = actor, objectUri = objectUri).copy(
            content = emoji.text,
            misskeyReaction = emoji.text,
            tag = when (emoji) {
                is ReactionEmoji.Unicode -> null
                is ReactionEmoji.Custom -> listOf(emoji.emojiObject)
            },
        )

    /**
     * 受け取る側は Undo に埋め込まれた Like の id で、取り消すいいねを見分ける。[like] には送ったときと同じ id を渡す
     *
     * @param id Like と同じ id にすると、id で重複を落とす相手に Undo を捨てられる
     */
    fun undo(id: String, like: OutgoingActivity): OutgoingActivity {
        require(like.type == ReactionHandler.LIKE_TYPE)
        require(id != like.id)
        return OutgoingActivity(
            id = id,
            type = "Undo",
            actor = like.actor,
            target = LinkOrObject.Embedded(AppJson.encodeToJsonElement(like).jsonObject),
        )
    }
}

sealed interface ReactionEmoji {
    /** 本文に入る形の文字 */
    val text: String

    data class Unicode(val emoji: String) : ReactionEmoji {
        override val text: String get() = emoji
    }

    /**
     * @param emojiObject [EmojiObject.name] が `:name:` の形
     */
    data class Custom(val emojiObject: EmojiObject) : ReactionEmoji {
        override val text: String get() = emojiObject.name
    }
}
