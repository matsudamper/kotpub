package net.matsudamper.activitypub.http

/**
 * [ActivityPubHttpClient] の呼び出し口。
 *
 * 公開コンストラクタが受けるのは検査付きの [ActivityPubHttpClient] だけにする。
 * この型はテストが通信を差し替えるためのもので、モジュールの外には出さない
 */
internal interface ActivityPubHttpCalls : AutoCloseable {
    /**
     * リダイレクトは追ってよい。追った場合は、最後に取りに行った URL を
     * [ActivityPubClientResponse.finalUrl] に入れること。別のホストに移っていないかを
     * 呼び出し側がそこで確かめる。
     *
     * 本文は [readsBody] が true を返したときだけ、[maxBodyBytes] を 1 バイト超えるところまで読むこと。
     * 全部読んでから大きさを確かめると、相手の送った分だけメモリを確保することになる。
     * status だけで捨てる応答の本文を読むと、相手が少しずつ送り続けるだけでタイムアウトまで待たされる
     *
     * @param readsBody status と [ActivityPubClientResponse.finalUrl] を受けて、本文を読むかを返す
     */
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        readsBody: (status: Int, finalUrl: String) -> Boolean,
    ): ActivityPubClientResponse

    /**
     * リダイレクトは追わないこと。追うと署名した `Host` やパスと違う宛先に
     * ボディごと POST し直すことになる。
     *
     * 応答の本文は読まずに捨てること。判断は status だけで足り、相手が巨大な本文や
     * 終わらない本文を返すと、届いた配信までメモリを食った末のタイムアウトで失敗になる
     */
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): ActivityPubPostResponse
}
