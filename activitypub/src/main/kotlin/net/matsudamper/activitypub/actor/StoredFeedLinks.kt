package net.matsudamper.activitypub.actor

/**
 * アクターが配信しているフィードの URL の引き先。
 *
 * どこに保存されているかは Actor 側の関心ではないので、
 * [StoredActorNames] と同じく名前を渡して引けることだけを決めておく。
 */
interface StoredFeedLinks {
    /**
     * 名前で引く。フィードを持たないアカウントは [FeedLinks.EMPTY]。
     *
     * 渡すのは [StoredActorNames] が返した保存側の綴り。
     */
    fun find(username: String): FeedLinks
}

/**
 * プロフィールに載せるフィードの URL。
 *
 * @param siteUrl フィードが指している Web サイトの URL
 * @param feedUrl 取得元の RSS/Atom の URL
 * @param iconUrl フィードが名乗っているアイコンの取得元。プロフィール画像を
 *   出せるかどうかの判断に使う。相手に渡すのは [ActorUrls.icon] の方
 * @param headerVersion 取り込み済みヘッダー画像の内容を表す版。相手に渡す URL の `v` に使う
 */
data class FeedLinks(
    val siteUrl: String?,
    val feedUrl: String?,
    val iconUrl: String?,
    val headerVersion: String?,
) {
    companion object {
        val EMPTY: FeedLinks = FeedLinks(siteUrl = null, feedUrl = null, iconUrl = null, headerVersion = null)
    }
}
