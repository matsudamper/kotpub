package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.note.RemoteNoteStore

class DeleteHandler(
    private val actors: DeleteActorHandler,
    private val notes: RemoteNoteStore,
) : InboxActivityHandler {
    override val type: String = "Delete"

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        val actor = activity.actorId ?: return InboxHandling.Ignored("Delete に actor が無い: $verifiedSignerActorId")
        if (actor != verifiedSignerActorId) return InboxHandling.Ignored("Delete の actor が署名者と違う: actor=$actor 署名者=$verifiedSignerActorId")
        val objectId = activity.target?.id ?: return InboxHandling.Ignored("Delete に object が無い: $verifiedSignerActorId")
        if (objectId == actor) {
            return actors.handle(recipient, verifiedSignerActorId, activity, rawActivityJson)
        }
        val embedded = activity.target as? LinkOrObject.Embedded
        if (embedded != null) {
            val objectType = (embedded.json["type"] as? JsonPrimitive)?.content
            if (objectType !in listOf("Note", "Tombstone")) return InboxHandling.Ignored("Delete の対象が投稿ではない: type=$objectType object=$objectId")
        }
        val actorUrl = HttpUrl.parse(actor) ?: return InboxHandling.Ignored("Delete の actor を URL として読めない: $actor")
        val objectUrl = HttpUrl.parse(objectId) ?: return InboxHandling.Ignored("Delete の object を URL として読めない: $objectId")
        if (!actorUrl.isSameHost(objectUrl)) return InboxHandling.Ignored("Delete の対象が actor と別のホスト: $objectId actor=$actor")
        notes.delete(actor, objectId)
        return InboxHandling.Handled
    }
}
