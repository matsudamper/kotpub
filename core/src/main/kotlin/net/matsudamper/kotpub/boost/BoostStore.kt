package net.matsudamper.kotpub.boost

import java.time.Instant
import net.matsudamper.kotpub.actor.RemoteActor
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.note.NoteVisibility
import net.matsudamper.kotpub.note.ReceivedNote

/**
 * 受け取ったブースト（`Announce`）の記録。
 *
 * こちらの投稿のブーストは件数と押した相手を出すために、他のサーバーの投稿のブーストは
 * タイムラインに出すために受け取る。どちらも `Undo` では `Announce` の id で取り消すので、
 * 取り消しは 1 つにまとめてある
 */
interface BoostStore {
    /**
     * こちらの投稿のブースト。同じ相手が同じ投稿をブーストし直しても増えない。
     *
     * @return 記録したら true。既にあるか、投稿が無ければ false
     */
    fun add(boost: ReceivedBoost): Boolean

    /**
     * 他のサーバーの投稿のブースト。誰に届けるかは記録する側が決める
     */
    suspend fun saveRemote(boost: ReceivedRemoteBoost)

    /**
     * @param actorUri ブーストした相手。他人のブーストを消されないよう、署名の持ち主で固定する
     * @param activityUri 取り消す `Announce` の id
     * @return 消したら true
     */
    fun remove(
        actorUri: String,
        activityUri: String,
    ): Boolean

    fun removeActor(actorUri: String): Int

    fun findPublicKeyPem(actorUri: String): String?

    /**
     * @param activityUri `Announce` の id。`Undo` で取り消すときの手掛かり
     */
    data class ReceivedBoost(
        val notePublicId: PublicNoteId,
        val actor: RemoteActor,
        val activityUri: String,
        val receivedAt: Instant,
    )

    /**
     * @param actorUri ブーストした相手。署名を検証済み
     * @param visibility `Announce` 自身の公開範囲。ブーストされた投稿の公開範囲は [note] が持つ
     * @param to `Announce` の宛先
     * @param cc `Announce` の宛先
     * @param published `Announce` の日時。無ければ null
     * @param note 投稿のあるサーバーから取得したブーストされた投稿
     */
    data class ReceivedRemoteBoost(
        val activityUri: String,
        val actorUri: String,
        val visibility: NoteVisibility,
        val to: List<String>,
        val cc: List<String>,
        val published: Instant?,
        val note: ReceivedNote,
    )
}
