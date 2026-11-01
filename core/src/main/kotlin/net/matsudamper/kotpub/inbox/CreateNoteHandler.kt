package net.matsudamper.kotpub.inbox

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.note.NoteVisibility
import net.matsudamper.kotpub.note.ReceivedNote
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
        val note = (activity.target as? LinkOrObject.Embedded)?.json ?: return
        if (note["type"].string() != "Note") return
        val actor = activity.actorId ?: return
        if (actor != verifiedSignerActorId || note["attributedTo"].string() != actor) return
        val id = note["id"].string() ?: return
        val actorUrl = HttpUrl.parse(actor) ?: return
        val noteUrl = HttpUrl.parse(id) ?: return
        if (!actorUrl.isSameHost(noteUrl)) return
        val to = note["to"].strings()
        val cc = note["cc"].strings()
        val visibility = when {
            PUBLIC in to -> NoteVisibility.Public
            PUBLIC in cc -> NoteVisibility.Unlisted
            (to + cc).any { it == "$actor/followers" } -> NoteVisibility.Followers
            else -> NoteVisibility.Direct
        }
        notes.save(
            ReceivedNote(
                id = id,
                attributedTo = actor,
                content = note["content"].string() ?: return,
                published = note["published"].string()?.let { runCatching { Instant.parse(it) }.getOrNull() },
                to = to,
                cc = cc,
                inReplyTo = note["inReplyTo"].string(),
                summary = note["summary"].string(),
                sensitive = (note["sensitive"] as? JsonPrimitive)?.booleanOrNull ?: false,
                visibility = visibility,
            ),
        )
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.strings(): List<String> = when (this) {
        is JsonArray -> mapNotNull { it.string() }
        else -> listOfNotNull(string())
    }

    private companion object {
        const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
    }
}
