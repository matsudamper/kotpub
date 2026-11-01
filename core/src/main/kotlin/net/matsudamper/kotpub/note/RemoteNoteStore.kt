package net.matsudamper.kotpub.note

import java.time.Instant

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
)

enum class NoteVisibility {
    Public,
    Unlisted,
    Followers,
    Direct,
}
