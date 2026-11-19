package net.matsudamper.kotpub.inbox

import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.RemoteFetchFailure
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
     * 投稿のあるサーバーが投稿は無いと答えたなら、保存してあるものは消えた投稿の写しなので、誰の Delete でも消してよい。
     * 消さないと、偽の Create が先に届いた投稿を本来の投稿者が消せない。
     *
     * 無いと言えるのは 404・410 だけ。401・403 や読めない文書は、投稿が残っていても返ってくるので消す根拠にならない
     */
    suspend fun canDelete(
        noteId: String,
        actorId: String,
    ): Boolean {
        if (isSavedBy(noteId, actorId)) return true
        return when (val lookup = remoteNotes.find(noteId)) {
            is RemoteNoteLookup.Found -> lookup.note.attributedTo == actorId
            is RemoteNoteLookup.NotFound -> lookup.failure.isAbsent()
            is RemoteNoteLookup.Unavailable -> throw temporarilyUnavailable(noteId, lookup)
        }
    }

    private fun RemoteFetchFailure.isAbsent(): Boolean =
        this is RemoteFetchFailure.UnsuccessfulStatus && (status == HttpStatusCodes.NOT_FOUND || status == HttpStatusCodes.GONE)

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
