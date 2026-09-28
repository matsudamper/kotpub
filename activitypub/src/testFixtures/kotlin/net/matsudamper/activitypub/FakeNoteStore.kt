package net.matsudamper.activitypub

import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.note.NotePosition
import net.matsudamper.activitypub.note.NoteStore
import net.matsudamper.activitypub.note.StoredNote

/**
 * 投稿の記録の差し替え。オンメモリで持つ。
 *
 * 記録する口は本物には無い（投稿の記録は配信の投函と 1 トランザクションで確定させる）ので、
 * テストが状態を作るための [add] だけを持つ
 */
class FakeNoteStore : NoteStore {
    val added: MutableList<StoredNote> = mutableListOf()

    fun add(note: StoredNote) {
        added += note
    }

    override fun find(publicId: PublicNoteId): StoredNote? = added.firstOrNull { it.publicId == publicId }

    override fun findByPublicIds(publicIds: Set<PublicNoteId>): Map<PublicNoteId, StoredNote> = added
        .filter { it.publicId in publicIds }
        .associateBy { it.publicId }

    override fun delete(publicId: PublicNoteId) {
        added.removeAll { it.publicId == publicId }
    }

    override fun deleteByUsername(username: String): Int {
        val before = added.size
        added.removeAll { it.username.equals(username, ignoreCase = true) }
        return before - added.size
    }

    override fun list(
        username: String,
        after: NotePosition?,
        limit: Int,
    ): List<StoredNote> = added
        .filter { it.username == username }
        .sortedWith(compareByDescending<StoredNote> { it.publishedAt }.thenByDescending { it.publicId.value })
        .filter { note -> after == null || note.isOlderThan(after) }
        .take(limit)

    override fun listPositions(
        username: String,
        after: NotePosition?,
        limit: Int,
    ): List<NotePosition> = list(username = username, after = after, limit = limit)
        .map { it.position }

    override fun listAllPositions(
        after: NotePosition?,
        limit: Int,
    ): List<NotePosition> = added
        .sortedWith(compareByDescending<StoredNote> { it.publishedAt }.thenByDescending { it.publicId.value })
        .filter { note -> after == null || note.isOlderThan(after) }
        .take(limit)
        .map { it.position }

    override fun count(username: String): Long = added.count { it.username == username }.toLong()

    override fun counts(usernames: Set<String>): Map<String, Long> =
        usernames.associateWith { count(it) }

    private fun StoredNote.isOlderThan(position: NotePosition): Boolean =
        publishedAt < position.publishedAt ||
            (publishedAt == position.publishedAt && publicId.value < position.publicId.value)
}
