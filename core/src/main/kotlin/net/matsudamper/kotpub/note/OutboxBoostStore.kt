package net.matsudamper.kotpub.note

import java.time.Instant
import net.matsudamper.kotpub.activity.BoostAudience

/**
 * こちらのアカウントが付けたブーストの保存先。outbox に投稿と一緒に並べる。
 * 実装は利用側のアプリが用意する
 */
interface OutboxBoostStore {
    /**
     * 新しい順に返す。並びは [StoredBoost.position] の新しい順で、[NoteStore.list] と同じく時刻、同じ時刻なら key の大きい順にする。
     * 投稿と同じ並べ方にしないと、outbox のページの境目で抜けや重なりが出る
     *
     * @param after ここより古いものを返す。null なら先頭から
     */
    fun list(
        username: String,
        after: OutboxPosition?,
        limit: Int,
    ): List<StoredBoost>

    fun count(username: String): Long
}

/**
 * outbox の一覧の続きを指す位置。投稿とブーストを同じ並びに混ぜるため、時刻と文字列の key で指す。
 * 投稿の key は公開 id、ブーストの key は Announce の id
 */
data class OutboxPosition(
    val at: Instant,
    val key: String,
)

/**
 * こちらのアカウントが付けたブースト 1 件。outbox には配ったときと同じ Announce を出す
 *
 * @param announceId 配った Announce の id
 * @param objectUri ブーストした投稿の id
 * @param objectAuthor ブーストした投稿の投稿者のアクター id
 * @param boostedAt ブーストした日時
 * @param audience 配ったときの見せる相手
 */
data class StoredBoost(
    val announceId: String,
    val objectUri: String,
    val objectAuthor: String,
    val boostedAt: Instant,
    val audience: BoostAudience,
) {
    val position: OutboxPosition get() = OutboxPosition(at = boostedAt, key = announceId)
}
