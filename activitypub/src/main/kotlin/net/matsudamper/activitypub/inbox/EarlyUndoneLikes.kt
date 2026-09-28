package net.matsudamper.activitypub.inbox

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * `Like` より先に届いた `Undo` が `object` で指していた `Like` アクティビティの `id` の置き先。
 * `EmojiReact` の `id` も同じくここに置く。
 *
 * 配送の順番は保証されず、取り消しが先に届くことがある。覚えておかないと、後から
 * 届いた `Like` だけが残り、相手が取り消したお気に入りやスタンプが数に入る。再起動をまたいで
 * 届くこともあるので、メモリではなく記録に残す。
 *
 * 相手ごとに分けて持つ。`id` だけで覚えると、他人の `Like` の `id` を先に送り込んで
 * その相手のお気に入りを弾ける。
 */
interface EarlyUndoneLikes {
    fun remember(
        actorUri: String,
        activityUri: String,
        expiresAt: Instant,
    )

    fun isRemembered(
        actorUri: String,
        activityUri: String,
        now: Instant,
    ): Boolean

    companion object {
        /**
         * Mastodon の `delete_later!` と同じ期間
         */
        val TTL: Duration = 6.hours
    }
}
