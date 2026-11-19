package net.matsudamper.kotpub.activity

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.json.AppJson

object FollowActivities {
    fun follow(id: String, actor: String, target: String): OutgoingActivity =
        OutgoingActivity(id = id, type = "Follow", actor = actor, target = LinkOrObject.Link(target))

    fun undo(id: String, follow: OutgoingActivity): OutgoingActivity {
        require(follow.type == "Follow")
        return OutgoingActivity(
            id = id,
            type = "Undo",
            actor = follow.actor,
            target = LinkOrObject.Embedded(AppJson.encodeToJsonElement(follow).jsonObject),
        )
    }

    /**
     * 受けた `Follow` を断る。成立済みのフォローに送ると、相手からのフォローを外したことになる
     *
     * @param actor 断るこちらのアクター。[follow] の宛先
     * @param follow 相手が送ってきた `Follow`。受け取る側は埋め込まれた `Follow` の id か、`actor` と `object` の組で探す
     */
    fun reject(id: String, actor: String, follow: OutgoingActivity): OutgoingActivity {
        require(follow.type == "Follow")
        return OutgoingActivity(
            id = id,
            type = OutgoingActivity.TYPE_REJECT,
            actor = actor,
            target = LinkOrObject.Embedded(AppJson.encodeToJsonElement(follow).jsonObject),
        )
    }
}
