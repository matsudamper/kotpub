package net.matsudamper.kotpub.note

import java.time.Instant
import net.matsudamper.kotpub.emoji.CustomEmoji

interface RemoteNoteStore {
    suspend fun save(note: ReceivedNote)
    suspend fun delete(actorId: String, objectId: String)
}

data class ReceivedNote(
    val id: String,
    val attributedTo: String,
    val content: String,
    val published: Instant?,
    val to: List<String>,
    val cc: List<String>,
    val inReplyTo: String?,
    val summary: String?,
    val sensitive: Boolean,
    val visibility: NoteVisibility,
    val attachments: List<ReceivedNoteAttachment>,
    val emojis: List<CustomEmoji>,
)

/**
 * @param mediaType 送り手が付けていなければ null
 * @param name 画像の説明文など。無ければ null
 */
data class ReceivedNoteAttachment(
    val url: String,
    val mediaType: String?,
    val name: String?,
)

enum class NoteVisibility {
    Public,
    Unlisted,
    Followers,
    Direct,
}
