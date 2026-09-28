package net.matsudamper.activitypub.httpsignature

import java.util.Base64

/**
 * `Signature` ヘッダの中身。
 *
 * 実際に飛んでくるのはこういう 1 行。
 *
 * ```
 * Signature: keyId="https://mastodon.social/users/foo#main-key",algorithm="rsa-sha256",
 *            headers="(request-target) host date digest content-type",signature="Base64=="
 * ```
 *
 * @param keyId 公開鍵の場所。ここを GET してアクターの `publicKey` を取る
 * @param algorithm 署名アルゴリズム。省略されることがあるので null を許す
 * @param headers 署名対象にしたヘッダ名。この並び順のまま署名文字列を組み立てる
 * @param signature Base64 をデコードした署名バイト列
 */
class SignatureHeader(
    val keyId: String,
    val algorithm: String?,
    val headers: List<String>,
    val signature: ByteArray,
) {
    companion object {
        /**
         * `headers` が省略されたときの既定値。
         *
         * draft-cavage-http-signatures では `date` だけを署名したものとして扱う。
         * 実際にこれで送ってくる実装はまず無いが、既定値を勝手に広げると
         * 署名されていないヘッダを署名済みとして扱うことになるので仕様どおりにする。
         */
        private val DEFAULT_HEADERS: List<String> = listOf("date")

        /**
         * ヘッダ値をパースする。形が壊れていれば null。
         *
         * 受信するのは相手の実装が作った文字列なので、想定外の並びで例外を投げると
         * ルーティングまで上がってしまう。ここで null に潰して 401 の材料にする。
         */
        fun parse(value: String): SignatureHeader? {
            val parameters = parseParameters(value)

            val keyId = parameters["keyid"]?.takeIf { it.isNotEmpty() } ?: return null
            val encodedSignature = parameters["signature"]?.takeIf { it.isNotEmpty() } ?: return null

            // 相手が送ってきた Base64。壊れていれば例外ではなく null にする
            val signature =
                runCatching { Base64.getDecoder().decode(encodedSignature) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null

            val headers =
                parameters["headers"]
                    ?.split(' ')
                    ?.filter { it.isNotEmpty() }
                    // ヘッダ名は大文字小文字を区別しない。署名文字列では小文字で組み立てる
                    ?.map { it.lowercase() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_HEADERS

            return SignatureHeader(
                keyId = keyId,
                algorithm = parameters["algorithm"]?.lowercase()?.takeIf { it.isNotEmpty() },
                headers = headers,
                signature = signature,
            )
        }

        /**
         * `name="value"` をカンマ区切りで並べたものを読む。
         *
         * 値には Base64 の `+` `/` `=` が入るので、`=` や `,` で機械的に分割はできない。
         * 引用符の内側を読み飛ばしながら前から見ていく。
         *
         * 引用符のエスケープ（`\"`）は扱わない。keyId は URL、signature は Base64 で、
         * どちらにも `"` は現れないため。
         *
         * パラメータ名は小文字に揃える。`keyId` と `keyid` のどちらで来ても引けるようにする。
         */
        private fun parseParameters(raw: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            var index = 0

            while (index < raw.length) {
                // 区切りのカンマと空白を読み飛ばす
                while (index < raw.length && (raw[index] == ',' || raw[index].isWhitespace())) {
                    index++
                }
                if (index >= raw.length) break

                val nameStart = index
                while (index < raw.length && raw[index] != '=' && raw[index] != ',') {
                    index++
                }
                // 名前だけあって値が無い。全体を信用できないので捨てる
                if (index >= raw.length || raw[index] != '=') return emptyMap()

                val name = raw.substring(nameStart, index).trim().lowercase()
                index++

                val value: String
                if (index < raw.length && raw[index] == '"') {
                    index++
                    val valueStart = index
                    while (index < raw.length && raw[index] != '"') {
                        index++
                    }
                    // 閉じ引用符が無い。どこまでが値か決められない
                    if (index >= raw.length) return emptyMap()
                    value = raw.substring(valueStart, index)
                    index++
                } else {
                    val valueStart = index
                    while (index < raw.length && raw[index] != ',') {
                        index++
                    }
                    value = raw.substring(valueStart, index).trim()
                }

                // 同じ名前が 2 回来たら先に出た方を採る。後から上書きされて
                // 検証に使う鍵が変わる、といった読み替えを起こさないため
                if (name.isNotEmpty()) result.putIfAbsent(name, value)
            }

            return result
        }
    }
}
