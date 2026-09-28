package net.matsudamper.activitypub.note

import java.time.Instant
import net.matsudamper.activitypub.entity.PublicNoteId

/**
 * 配信した投稿の置き先。
 *
 * [net.matsudamper.activitypub.follower.FollowerStore] と同じく口だけを決めて、
 * 実装は `:backend` が repository に繋ぐ。
 *
 * 送ったら終わりにはできない。Mastodon は受け取った投稿のパーマリンクを後から
 * 引きに来るので、送った中身をこちらにも残しておく必要がある。
 *
 * 記録する口は無い。投稿の記録は配信の投函と 1 トランザクションで確定させる必要があり、
 * それは `:backend` が repository の投函の口で行う。
 */
interface NoteStore {
    fun find(publicId: PublicNoteId): StoredNote?

    /**
     * 消す。`Delete` を配る前に呼ぶ。配信が先だと、消したことを受け取った相手が
     * 確かめに来たときにまだ本文を返してしまう
     */
    fun delete(publicId: PublicNoteId)

    /**
     * そのアカウントの投稿を全部消す。アカウントを消すときに使う
     *
     * @return 消えた件数
     */
    fun deleteByUsername(username: String): Int

    fun findByPublicIds(publicIds: Set<PublicNoteId>): Map<PublicNoteId, StoredNote>

    /**
     * 新しい順に返す。
     *
     * 位置を件数で数えず、直前のページの最後の 1 件で指す。件数で数えると、
     * 読んでいる間に新しい投稿が入るたびに位置がずれて、同じ投稿が 2 回出たり
     * 抜けたりする。投稿は先頭に増えるので、件数で数えると必ずずれる。
     *
     * @param after ここより古いものを返す。null なら先頭から
     */
    fun list(
        username: String,
        after: NotePosition?,
        limit: Int,
    ): List<StoredNote>

    /**
     * 新しい順に位置だけ返す。本文は取らない
     */
    fun listPositions(
        username: String,
        after: NotePosition?,
        limit: Int,
    ): List<NotePosition>

    /**
     * アカウントを問わず、新しい順に位置だけ返す
     */
    fun listAllPositions(
        after: NotePosition?,
        limit: Int,
    ): List<NotePosition>

    fun count(username: String): Long

    fun counts(usernames: Set<String>): Map<String, Long>
}

/**
 * 一覧の続きを指す位置。
 *
 * `publishedAt` だけでは同じ時刻の投稿が並んだときに位置が決まらないので、
 * `publicId` まで見て一意にする。外に出す形は口ごとに決める
 */
data class NotePosition(
    val publishedAt: Instant,
    val publicId: PublicNoteId,
)

/**
 * 配信した投稿 1 件。
 *
 * @param publicId URL のパスに入る識別子
 * @param username 投稿したこちらのアカウントの名前
 * @param contentHtml 配信した本文の HTML
 * @param publishedAt 相手に見せる公開日時
 */
data class StoredNote(
    val publicId: PublicNoteId,
    val username: String,
    val contentHtml: String,
    val publishedAt: Instant,
) {
    /**
     * この投稿を「直前のページの最後」として指す位置
     */
    val position: NotePosition get() = NotePosition(publishedAt = publishedAt, publicId = publicId)
}
