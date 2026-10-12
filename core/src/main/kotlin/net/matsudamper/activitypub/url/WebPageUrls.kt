package net.matsudamper.activitypub.url

import net.matsudamper.activitypub.entity.PublicNoteId

/**
 * 人が開くページの URL。ActivityPub の `url` に入れる。
 *
 * Mastodon はリモートのアカウントと投稿を取り込むとき、`id` を通信用の識別子、
 * `url` を人が開くリンク先として別々に持つ。`url` が無ければ `id` にフォールバックし、
 * 相手のプロフィールやパーマリンクから JSON を返すパスが開くことになる。
 *
 * どのパスにどの画面を出すかを決めるのはこの module ではないので、組み立ては外から受け取る。
 * 画面を出さない構成では渡さない（null）。`url` を出さなければ相手は `id` に倒すので、
 * 開けないページを指すより、JSON のパスが開く方がまだ読める。
 */
interface WebPageUrls {
    /**
     * アカウントの画面
     */
    fun profile(username: String): String

    /**
     * 投稿 1 件の画面
     */
    fun note(
        username: String,
        publicId: PublicNoteId,
    ): String
}
