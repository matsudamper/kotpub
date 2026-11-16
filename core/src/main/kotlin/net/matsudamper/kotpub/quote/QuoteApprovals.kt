package net.matsudamper.kotpub.quote

import net.matsudamper.kotpub.actor.RemoteActor

/**
 * 引用を承認した `Accept` の配信先。
 *
 * [net.matsudamper.kotpub.follower.FollowerStore] と同じく、送るのは配信キューに任せる。
 * inbox の応答を相手のサーバーの都合で待たせないため。
 */
interface QuoteApprovals {
    /**
     * `Accept` を配信キューに登録する。登録できなければ投げる。inbox が 503 を返して送り直してもらう
     *
     * @param username 引用された投稿の持ち主。`Accept` の送り主になる
     * @param quoter 引用した相手。`Accept` はこの inbox に送る
     * @param acceptBody 送る `Accept` の JSON
     * @return 受け付けない相手なので登録しなかったら false
     */
    fun enqueueAccept(
        username: String,
        quoter: RemoteActor,
        acceptBody: String,
    ): Boolean
}
