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
    suspend fun decideWrite(
        noteId: String,
        actorId: String,
    ): NoteWriteDecision {
        val savedAttributedTo = notes.findAttributedTo(noteId)
        if (savedAttributedTo == null || savedAttributedTo == actorId) return NoteWriteDecision.Allowed(savedAttributedTo)
        return when (val lookup = remoteNotes.find(noteId)) {
            is RemoteNoteLookup.Found -> allowedIf(lookup.note.attributedTo == actorId, savedAttributedTo)
            is RemoteNoteLookup.NotFound -> NoteWriteDecision.Denied
            is RemoteNoteLookup.Unavailable -> throw temporarilyUnavailable(noteId, lookup)
        }
    }

    /**
     * 投稿のあるサーバーが投稿は無いと答えたなら、保存してあるものは消えた投稿の写しなので、誰の Delete でも消してよい。
     * 消さないと、偽の Create が先に届いた投稿を本来の投稿者が消せない。
     *
     * 無いと言えるのは 404・410 だけ。401・403 や読めない文書は、投稿が残っていても返ってくるので消す根拠にならない
     */
    suspend fun decideDelete(
        noteId: String,
        actorId: String,
    ): NoteWriteDecision {
        val savedAttributedTo = notes.findAttributedTo(noteId)
        if (savedAttributedTo == null || savedAttributedTo == actorId) return NoteWriteDecision.Allowed(savedAttributedTo)
        return when (val lookup = remoteNotes.find(noteId)) {
            is RemoteNoteLookup.Found -> allowedIf(lookup.note.attributedTo == actorId, savedAttributedTo)
            is RemoteNoteLookup.NotFound -> allowedIf(lookup.failure.isAbsent(), savedAttributedTo)
            is RemoteNoteLookup.Unavailable -> throw temporarilyUnavailable(noteId, lookup)
        }
    }

    private fun allowedIf(
        allowed: Boolean,
        savedAttributedTo: String,
    ): NoteWriteDecision = if (allowed) NoteWriteDecision.Allowed(savedAttributedTo) else NoteWriteDecision.Denied

    private fun RemoteFetchFailure.isAbsent(): Boolean =
        this is RemoteFetchFailure.UnsuccessfulStatus && (status == HttpStatusCodes.NOT_FOUND || status == HttpStatusCodes.GONE)

    /**
     * 捨てると相手は送り直さず、偽の投稿が残る。投げて inbox から送り直してもらう
     */
    private fun temporarilyUnavailable(
        noteId: String,
        lookup: RemoteNoteLookup.Unavailable,
    ): IllegalStateException = IllegalStateException("投稿者を確かめる投稿を今は取得できない: $noteId 理由=${lookup.failure}")
}

internal sealed interface NoteWriteDecision {
    /**
     * @param savedAttributedTo 判断したときの保存済みの投稿者。保存していなければ null。
     *   書き込むときに [RemoteNoteStore] へ渡し、判断の後に書き換わっていないことを確かめてもらう
     */
    data class Allowed(val savedAttributedTo: String?) : NoteWriteDecision

    data object Denied : NoteWriteDecision
}

/**
 * 判断してから書くまでの間に、同じ投稿への別の Create・Update・Delete が書き換えた。
 * 捨てると相手は送り直さないので、投げて送り直してもらい、そのときの保存状態で判断し直す
 */
internal fun writeConflict(noteId: String): IllegalStateException =
    IllegalStateException("判断した後に保存済みの投稿者が書き換わった: $noteId")
