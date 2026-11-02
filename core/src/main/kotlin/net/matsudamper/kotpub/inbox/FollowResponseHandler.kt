package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.follower.FollowingResponses
import net.matsudamper.kotpub.json.AppJson

class FollowResponseHandler(
    override val type: String,
    private val responses: FollowingResponses,
) : InboxActivityHandler {
    init {
        require(type in listOf("Accept", "Reject"))
    }

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ) {
        if (activity.actorId != verifiedSignerActorId) return
        val target = activity.target ?: return
        if (target is LinkOrObject.Embedded) {
            val follow = runCatching { AppJson.decodeFromJsonElement(InboxActivity.serializer(), target.json) }.getOrNull() ?: return
            if (follow.type != "Follow" || follow.target?.id != verifiedSignerActorId) return
        }
        val followId = target.id ?: return
        if (type == "Accept") responses.accept(verifiedSignerActorId, followId) else responses.reject(verifiedSignerActorId, followId)
    }
}
