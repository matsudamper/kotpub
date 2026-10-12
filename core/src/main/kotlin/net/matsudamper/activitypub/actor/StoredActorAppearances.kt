package net.matsudamper.activitypub.actor

/**
 * アクターの見た目（プロフィールのリンク集と画像）の引き先。
 *
 * 何をリンクに並べ、画像をどこから持ってくるかは使う側の都合なので、
 * [StoredActorNames] と同じく名前を渡して引けることだけを決めておく。
 */
interface StoredActorAppearances {
    /**
     * 名前で引く。何も持たないアカウントは [ActorAppearance.EMPTY]。
     *
     * 渡すのは [StoredActorNames] が返した保存側の綴り。
     */
    fun find(username: String): ActorAppearance
}

/**
 * Actor の `attachment`・`icon`・`image` に載せるもの。
 *
 * @param links プロフィールのリンク集。並べた順に出す
 * @param iconVersion プロフィール画像の版。null なら `icon` を出さない。
 *   相手に渡す URL の `v` に使う（[ActorUrls.icon]）。相手は画像を URL で覚えるので、
 *   中身を差し替えたときは違う値にすること
 * @param headerVersion プロフィールヘッダーの版。null なら `image` を出さない。
 *   扱いは [iconVersion] と同じ（[ActorUrls.header]）
 */
data class ActorAppearance(
    val links: List<ActorLink>,
    val iconVersion: String?,
    val headerVersion: String?,
) {
    companion object {
        val EMPTY: ActorAppearance = ActorAppearance(links = listOf(), iconVersion = null, headerVersion = null)
    }
}

/**
 * プロフィールのリンク集の 1 行。
 *
 * @param name 見出し。Mastodon ではリンクの左に出る
 * @param url リンク先。`http` と `https` 以外は出さない
 */
data class ActorLink(
    val name: String,
    val url: String,
)
