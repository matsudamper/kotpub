package net.matsudamper.kotpub.inbox

import net.matsudamper.kotpub.note.RemoteNoteLookup
import net.matsudamper.kotpub.note.RemoteNoteStore
import net.matsudamper.kotpub.note.RemoteNotes

/**
 * 届いた投稿の Create・Update・Delete を、保存済みの投稿の持ち主として受けてよいか。
 *
 * 同じホストの別のアクターが他人の投稿 id で送ってくると、署名者・actor・attributedTo の一致と
 * ホストの一致だけでは見分けられない。保存済みの投稿者と突き合わせる。
 *
 * 保存済みの投稿者も、最初に届いた Create が名乗った値でしかない。偽の Create が先に届いていると
 * 本来の投稿者を締め出すことになるので、食い違ったときは投稿のあるサーバーから取り直し、そちらを正とする
 */
internal class NoteAuthorship(
    private val notes: RemoteNoteStore,
    private val remoteNotes: RemoteNotes,
) {
    suspend fun canWrite(
        noteId: String,
        actorId: String,
    ): Boolean {
        if (isSavedBy(noteId, actorId)) return true
        return when (val lookup = remoteNotes.find(noteId)) {
            is RemoteNoteLookup.Found -> lookup.note.attributedTo == actorId
            is RemoteNoteLookup.NotFound -> false
            is RemoteNoteLookup.Unavailable -> throw temporarilyUnavailable(noteId, lookup)
        }
    }

    /**
     * 投稿のあるサーバーが投稿を返さないなら、保存してあるものは消えた投稿の写しなので、誰の Delete でも消してよい。
     * 消さないと、偽の Create が先に届いた投稿を本来の投稿者が消せない
     */
    suspend fun canDelete(
        noteId: String,
        actorId: String,
    ): Boolean {
        if (isSavedBy(noteId, actorId)) return true
        return when (val lookup = remoteNotes.find(noteId)) {
            is RemoteNoteLookup.Found -> lookup.note.attributedTo == actorId
            is RemoteNoteLookup.NotFound -> true
            is RemoteNoteLookup.Unavailable -> throw temporarilyUnavailable(noteId, lookup)
        }
    }

    /**
     * 捨てると相手は送り直さず、偽の投稿が残る。投げて inbox から送り直してもらう
     */
    private fun temporarilyUnavailable(
        noteId: String,
        lookup: RemoteNoteLookup.Unavailable,
    ): IllegalStateException = IllegalStateException("投稿者を確かめる投稿を今は取得できない: $noteId 理由=${lookup.failure}")

    private suspend fun isSavedBy(
        noteId: String,
        actorId: String,
    ): Boolean {
        val savedAuthor = notes.findAttributedTo(noteId) ?: return true
        return savedAuthor == actorId
    }
}
