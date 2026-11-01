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
}
