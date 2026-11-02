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
    ) {
        val actor = activity.actorId ?: return
        if (actor != verifiedSignerActorId) return
        val objectId = activity.target?.id ?: return
        if (objectId == actor) {
            actors.handle(recipient, verifiedSignerActorId, activity, rawActivityJson)
            return
        }
        val embedded = activity.target as? LinkOrObject.Embedded
        if (embedded != null) {
            val objectType = (embedded.json["type"] as? JsonPrimitive)?.content
            if (objectType !in listOf("Note", "Tombstone")) return
        }
        val actorUrl = HttpUrl.parse(actor) ?: return
        val objectUrl = HttpUrl.parse(objectId) ?: return
        if (!actorUrl.isSameHost(objectUrl)) return
        notes.delete(actor, objectId)
    }
}
