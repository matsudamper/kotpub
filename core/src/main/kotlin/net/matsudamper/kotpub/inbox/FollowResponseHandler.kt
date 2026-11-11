package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.follower.FollowingResponses
import net.matsudamper.kotpub.json.AppJson
import org.slf4j.LoggerFactory

class FollowResponseHandler(
    override val type: String,
    private val responses: FollowingResponses,
) : InboxActivityHandler {
    private val logger = LoggerFactory.getLogger(FollowResponseHandler::class.java)

    init {
        require(type in listOf("Accept", "Reject"))
    }

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ) {
        if (activity.actorId != verifiedSignerActorId) {
            logger.warn("$type の actor が署名者と違うので何もしない: actor=${activity.actorId} 署名者=$verifiedSignerActorId")
            return
        }
        val target = activity.target
        if (target == null) {
            logger.warn("$type に object が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
            return
        }
        if (target is LinkOrObject.Embedded) {
            val follow = runCatching { AppJson.decodeFromJsonElement(InboxActivity.serializer(), target.json) }.getOrElse { failure ->
                logger.warn("$type の object を読めなかった: ${recipient.logLabel} ← $verifiedSignerActorId", failure)
                return
            }
            if (follow.type != "Follow" || follow.target?.id != verifiedSignerActorId) {
                logger.info("$type の対象が署名者への Follow ではないので何もしない: ${recipient.logLabel} ← $verifiedSignerActorId")
                return
            }
        }
        val followId = target.id
        if (followId == null) {
            logger.warn("$type の対象の Follow に id が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
            return
        }
        if (type == "Accept") responses.accept(verifiedSignerActorId, followId) else responses.reject(verifiedSignerActorId, followId)
    }
}
