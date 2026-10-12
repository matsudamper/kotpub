package net.matsudamper.activitypub.inbox

/**
 * inbox から見た、相手のドメインごとの扱い。
 *
 * どのドメインを止めるかは使う側が決める。ここでは URL を渡すだけで、ドメインの取り出し方も持たない
 */
interface InboxDomainBlocks {
    /**
     * その URL の持ち主からの受信を止めているか
     *
     * @param url 署名の keyId。署名を検証する前に見るので、まだ信用できない
     */
    fun blocksInboxFrom(url: String): Boolean

    /**
     * 署名を検証できたリクエストが届いた。
     *
     * Mastodon は、配信できなくなったドメインを、そのドメインのアクターから
     * 署名付きリクエストが届いたときに配信先へ戻す。相手のサーバーが動いていることが分かるため。
     *
     * @param verifiedSignerActorId 署名を検証できた鍵の持ち主
     */
    fun signedRequestReceived(verifiedSignerActorId: String)
}
