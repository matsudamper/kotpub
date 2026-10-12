package net.matsudamper.activitypub.httpsignature

/**
 * 署名の対象になる文字列を組み立てる。
 *
 * `Signature` ヘッダの `headers` に並んだ順で 1 行ずつ作り、`\n` で繋ぐ。
 * 末尾に改行は付けない。
 *
 * ```
 * (request-target): post /users/admin/inbox
 * host: example.com
 * date: Tue, 20 Apr 2021 02:07:55 GMT
 * digest: SHA-256=xxxx
 * ```
 *
 * 送信側が署名したときの文字列と 1 バイトでも違えば検証は落ちる。
 * 検証も送信もここを通し、組み立てを 2 か所に書かない。
 */
object SigningString {
    /** ヘッダではなくメソッドとパスを表す擬似ヘッダ */
    const val REQUEST_TARGET: String = "(request-target)"

    /**
     * 署名文字列を作る。並びの中に無いヘッダがあれば null。
     *
     * 欠けたヘッダを空文字で埋めると、送信側が署名した内容と違うものを
     * 検証してしまう。組み立てられないことを呼び出し側に返して 401 にする。
     */
    fun build(
        request: SignedRequest,
        headerNames: List<String>,
    ): String? {
        if (headerNames.isEmpty()) return null

        val lines =
            headerNames.map { name ->
                when {
                    name == REQUEST_TARGET -> {
                        "$REQUEST_TARGET: ${request.method.lowercase()} ${request.requestTarget}"
                    }

                    // (created) や (expires) は署名アルゴリズムが hs2019 のときの擬似ヘッダ。
                    // 対応していないものを黙って無視すると検証が通ってしまうので組み立てを諦める
                    name.startsWith("(") -> {
                        return null
                    }

                    else -> {
                        // 同じヘッダが複数行で来た場合は ", " で繋ぐ（RFC 9110 の結合規則）
                        val value = request.headers.getAll(name)?.joinToString(", ") ?: return null
                        "$name: $value"
                    }
                }
            }

        return lines.joinToString("\n")
    }
}
