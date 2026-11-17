package net.matsudamper.kotpub.http

import kotlinx.coroutines.CancellationException

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
 * - 公開アドレスにだけ接続する。宛先の URL は相手が決めるので、検査しないと内部のサービスへ
 *   リクエストを送らされる。接続に使うアドレスを [PublicAddressDnsResolver] で解決すれば満たせる。
 *   名前解決をやり直すクライアントや、プロキシを通すクライアントでは満たせない
 */
interface ActivityPubHttpClient : AutoCloseable {
    /**
     * リダイレクトは追ってよい。追った場合は、最後に取得した URL を
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
 * @param body 本文が読めなくても status は使えるので、読めなかったことで status ごと捨てないこと。
 *   410 の本文が読めなかっただけで、消えたことが分からなくなる
 */
class ActivityPubClientResponse(
    val status: Int,
    val finalUrl: String,
    val body: ResponseBody,
)

/**
 * 応答の本文。「読まなかった」「読めなかった」「読めた」を null と Result の組み合わせで表すと、
 * 呼び出し側は読んだはずの本文の null を確かめ直し、失敗に紛れたキャンセルを取り分ける必要がある
 */
sealed interface ResponseBody {
    /** [ActivityPubHttpClient.get] の readsBody が false を返したので読んでいない */
    data object NotRead : ResponseBody

    class Read(val bytes: ByteArray) : ResponseBody

    /**
     * 読もうとして失敗した。呼び出し元のキャンセルはここに入れず、そのまま投げること
     */
    class Unreadable(val failure: Exception) : ResponseBody {
        init {
            require(failure !is CancellationException) { "キャンセルは本文の失敗にせず投げること" }
        }
    }
}

class ActivityPubPostResponse(
    val status: Int,
)

/**
 * リダイレクトを追わない 1 回分の GET もできる [ActivityPubHttpClient]。
 *
 * 署名した GET はリダイレクトの移り先ごとに署名を作り直さないと、移り先の `(request-target)` と
 * 署名が食い違って拒まれる。クライアントが自分で追うと作り直す機会が無いので、追う側に任せる
 */
interface SingleHopActivityPubHttpClient : ActivityPubHttpClient {
    /**
     * リダイレクトを追わずに 1 回だけ GET する。宛先を公開アドレスに限るのは [ActivityPubHttpClient.get] と同じ。
     *
     * 応答がリダイレクトなら本文を読まずに [SingleHopGetResponse.Redirect] を返す。
     * それ以外は [ActivityPubHttpClient.get] と同じく [readsBody] に従って本文を読む
     */
    suspend fun getSingleHop(
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        readsBody: (status: Int, finalUrl: String) -> Boolean,
    ): SingleHopGetResponse
}

sealed interface SingleHopGetResponse {
    /**
     * @param location `Location` ヘッダそのまま。相対 URL のこともあるので、解決は追う側がする
     */
    data class Redirect(val location: String) : SingleHopGetResponse

    class Final(val response: ActivityPubClientResponse) : SingleHopGetResponse
}
