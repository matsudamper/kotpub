package net.matsudamper.kotpub.follower

/**
 * こちらのアカウントがフォローしている相手の記録。実装は利用側のアプリが用意する。
 *
 * 相手が `Accept` を返して成立したものだけを出す。返事待ちのものを出すと、
 * 相手から見ると成立していないフォローが一覧に載る。
 */
interface FollowingStore {
    /**
     * フォローしている相手のアクター URL を URL 順に返す。
     *
     * 位置は件数ではなく直前のページの最後の 1 件で指す。件数で数えると、
     * 読んでいる間にフォローや解除が入るたびに位置がずれる。
     *
     * @param after この URL より後ろを返す。null なら先頭から
     */
    fun list(
        username: String,
        after: String?,
        limit: Int,
    ): List<String>

    fun count(username: String): Long
}
