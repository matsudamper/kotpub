package net.matsudamper.activitypub.http

import kotlinx.serialization.SerializationStrategy
import net.matsudamper.activitypub.json.AppJson

/**
 * エンドポイントが返す応答。サーバーの実装はこれをそのまま書き出す。
 *
 * @param contentType `Content-Type` にそのまま載せる値
 * @param headers `Content-Type` 以外に付けるヘッダ
 */
class EndpointResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String>,
) {
    companion object {
        private const val TEXT_PLAIN = "text/plain; charset=UTF-8"

        fun text(
            status: Int,
            text: String,
            headers: Map<String, String>,
        ): EndpointResponse =
            EndpointResponse(
                status = status,
                contentType = TEXT_PLAIN,
                body = text.toByteArray(Charsets.UTF_8),
                headers = headers,
            )

        /**
         * serializer を明示して JSON にする。
         *
         * 値の型からリフレクションで serializer を引く形にすると、native-image では
         * 解決できず実行時に落ちる。引数で受けてコンパイル時に決めておく。
         *
         * @param contentType ActivityPub のエンドポイントでは `application/json` ではなく
         *   [net.matsudamper.activitypub.activitypub.ActivityPubContentTypes] の値を渡す
         */
        fun <T> json(
            serializer: SerializationStrategy<T>,
            value: T,
            contentType: MediaType,
        ): EndpointResponse =
            EndpointResponse(
                status = HttpStatusCodes.OK,
                contentType = contentType.toString(),
                body = AppJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8),
                headers = mapOf(),
            )
    }
}
