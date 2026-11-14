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
    ): InboxHandling {
        val json = (activity.target as? LinkOrObject.Embedded)?.json
            ?: return InboxHandling.Ignored("Create に投稿が埋め込まれていない: $verifiedSignerActorId")
        val note = ReceivedNoteJson.read(json)
            ?: return InboxHandling.Ignored("Create の object を投稿として読めない: $verifiedSignerActorId")
        val actor = activity.actorId ?: return InboxHandling.Ignored("Create に actor が無い: $verifiedSignerActorId")
        if (actor != verifiedSignerActorId || note.attributedTo != actor) {
            return InboxHandling.Ignored("Create の actor か投稿者が署名者と違う: actor=$actor 投稿者=${note.attributedTo} 署名者=$verifiedSignerActorId")
        }
        val actorUrl = HttpUrl.parse(actor) ?: return InboxHandling.Ignored("Create の actor を URL として読めない: $actor")
        val noteUrl = HttpUrl.parse(note.id) ?: return InboxHandling.Ignored("投稿の id を URL として読めない: ${note.id}")
        if (!actorUrl.isSameHost(noteUrl)) return InboxHandling.Ignored("投稿が actor と別のホスト: ${note.id} actor=$actor")
        notes.save(note)
        return InboxHandling.Handled
    }
}
