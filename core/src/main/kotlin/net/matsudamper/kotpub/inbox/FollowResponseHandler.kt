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
    ): InboxHandling {
        if (activity.actorId != verifiedSignerActorId) {
            return InboxHandling.Ignored("$type の actor が署名者と違う: actor=${activity.actorId} 署名者=$verifiedSignerActorId")
        }
        val target = activity.target ?: return InboxHandling.Ignored("$type に object が無い: $verifiedSignerActorId")
        if (target is LinkOrObject.Embedded) {
            val follow = runCatching { AppJson.decodeFromJsonElement(InboxActivity.serializer(), target.json) }.getOrNull()
                ?: return InboxHandling.Ignored("$type の object を読めない: $verifiedSignerActorId")
            if (follow.type != "Follow" || follow.target?.id != verifiedSignerActorId) {
                return InboxHandling.Ignored("$type の対象が署名者への Follow ではない: type=${follow.type} object=${follow.target?.id} 署名者=$verifiedSignerActorId")
            }
        }
        val followId = target.id ?: return InboxHandling.Ignored("$type の対象の Follow に id が無い: $verifiedSignerActorId")
        if (type == "Accept") responses.accept(verifiedSignerActorId, followId) else responses.reject(verifiedSignerActorId, followId)
        return InboxHandling.Handled
    }
}
