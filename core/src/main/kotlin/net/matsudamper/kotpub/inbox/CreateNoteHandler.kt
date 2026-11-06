package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.note.ReceivedNoteJson
import net.matsudamper.kotpub.note.RemoteNoteStore

class CreateNoteHandler(
    private val notes: RemoteNoteStore,
) : InboxActivityHandler {
    override val type: String = "Create"

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ) {
        val json = (activity.target as? LinkOrObject.Embedded)?.json ?: return
        val note = ReceivedNoteJson.read(json) ?: return
        val actor = activity.actorId ?: return
        if (actor != verifiedSignerActorId || note.attributedTo != actor) return
        val actorUrl = HttpUrl.parse(actor) ?: return
        val noteUrl = HttpUrl.parse(note.id) ?: return
        if (!actorUrl.isSameHost(noteUrl)) return
        notes.save(note)
    }
}
