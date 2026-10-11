package net.matsudamper.activitypub.http

import java.net.InetAddress

/**
 * 検査済みの [InetAddress] へ 1 回だけ HTTP する。
 * リダイレクトは追わない。追うかどうかは呼び出し側がホップごとに決める
 */
internal interface PinnedHttpTransport : AutoCloseable {
    fun exchange(
        request: PinnedHttpRequest,
        readsBody: (status: Int, headers: ResponseHeaders) -> Boolean,
        maxBodyBytes: Int,
    ): PinnedHttpResponse
}

internal class PinnedHttpRequest(
    val method: String,
    val url: HttpUrl,
    val address: InetAddress,
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal class PinnedHttpResponse(
    val status: Int,
    val headers: ResponseHeaders,
    /**
     * 本文を読まなかったときは null。読もうとして失敗したときは失敗を持つ。
     * status は本文が読めなくても使える
     */
    val body: Result<ByteArray>?,
)

internal class ResponseHeaders(
    private val values: List<Pair<String, String>>,
) {
    fun first(name: String): String? =
        values.firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }?.second
}
