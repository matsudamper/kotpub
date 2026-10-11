package net.matsudamper.activitypub.http

/**
 * 相手のサーバーへ出ていく HTTP。
 *
 * 通信の実装を差し替えられるように、ここで決めた約束だけを使う。
 * 実装は次を守ること。
 *
 * - 4xx や 5xx でも例外にせず、[ActivityPubClientResponse.status] に入れて返す。
 *   送り直すかどうか、消えたとみなすかは呼び出し側が status で決める
 * - 届かなかった（接続できない・タイムアウト）ときは例外を投げる
 * - 渡したヘッダを書き換えない。`Host` と `Date` は署名に入っていて、
 *   後から付け直されると相手側で署名が一致しなくなる
 * - 相手が応答しないまま待ち続けない。inbox の処理や配信が詰まる
 */
interface ActivityPubHttpClient : AutoCloseable {
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

/**
 * @param finalUrl リダイレクトを追った後の URL。追っていなければ要求した URL
 * @param body 本文。読まなかったときは null、読めなかったときは失敗として持つ。
 *   本文が読めなくても status は使えるので、読めなかったことで status ごと捨てないこと。
 *   410 の本文が読めなかっただけで、消えたことが分からなくなる
 */
class ActivityPubClientResponse(
    val status: Int,
    val finalUrl: String,
    val body: Result<ByteArray>?,
)

class ActivityPubPostResponse(
    val status: Int,
)
