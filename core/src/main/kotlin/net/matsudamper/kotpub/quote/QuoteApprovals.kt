package net.matsudamper.kotpub.quote

import net.matsudamper.kotpub.actor.LocalActor
import net.matsudamper.kotpub.actor.RemoteActor

/**
 * 引用の承認に要る、こちらのアカウントの情報と `Accept` の配信先。
 *
 * [net.matsudamper.kotpub.follower.FollowerStore] と同じく、送るのは配信キューに任せる。
 * inbox の応答を相手のサーバーの都合で待たせないため。
 */
interface QuoteApprovals {
    /**
     * 引用された投稿の持ち主。承認の URL の署名に秘密鍵を使う。アカウントが無ければ null
     */
    fun findAuthor(username: String): LocalActor?

    /**
     * 引用を認めない相手か。ブロックした相手なら true。
     * 承認した後にブロックしたときも、承認の取得に応えなくなる
     *
     * @param username 引用された投稿の持ち主
     * @param quoterActorId 引用した相手のアクター id
     */
    fun refuses(
        username: String,
        quoterActorId: String,
    ): Boolean

    /**
     * `Accept` を配信キューに登録する。登録できなければ投げる。inbox が 503 を返して送り直してもらう
     *
     * @param username 引用された投稿の持ち主。`Accept` の送り主になる
     * @param quoter 引用した相手。`Accept` はこの inbox に送る
     * @param acceptBody 送る `Accept` の JSON
     */
    fun enqueueAccept(
        username: String,
        quoter: RemoteActor,
        acceptBody: String,
    )
}
