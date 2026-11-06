package net.matsudamper.kotpub.activity

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activitypub.LinkOrObject
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
