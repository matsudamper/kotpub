package net.matsudamper.activitypub.httpsignature

import java.security.MessageDigest
import java.util.Base64

/**
 * `Digest` ヘッダとボディの突き合わせ。
 *
 * HTTP Signatures が署名するのはヘッダだけで、ボディは署名対象に入らない。
 * ボディの同一性は `Digest` ヘッダを署名対象に含めることで担保する。
 * つまり `Digest` を検証しない限り、署名が通ってもボディは差し替え可能になる。
 *
 * 形式は `SHA-256=<Base64>`。Mastodon はこの綴りで送ってくる。
 */
object BodyDigest {
    private const val ALGORITHM = "SHA-256"

    /** 送信時に付ける `Digest` ヘッダの値を作る */
    fun of(body: ByteArray): String = "$ALGORITHM=${Base64.getEncoder().encodeToString(sha256(body))}"

    /**
     * `Digest` ヘッダの値がボディと一致するか。
     *
     * 複数のダイジェストがカンマ区切りで入ることがある。SHA-256 のものだけを見て、
     * 1 つも入っていなければ false。SHA-1 しか無いものを通すと弱い方に合わせることになる。
     */
    fun matches(
        headerValue: String,
        body: ByteArray,
    ): Boolean {
        val expected = sha256(body)

        for (entry in headerValue.split(',')) {
            val separator = entry.indexOf('=')
            if (separator < 0) continue

            val algorithm = entry.substring(0, separator).trim()
            // 値の側の Base64 にも = が入るので、区切りは最初の = だけ
            val encoded = entry.substring(separator + 1).trim()
            if (!algorithm.equals(ALGORITHM, ignoreCase = true)) continue

            val actual = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: continue
            if (MessageDigest.isEqual(expected, actual)) return true
        }

        // SHA-256 が入っていない、あるいは一致しない。SHA-1 しか無いものもここで落ちる
        return false
    }

    private fun sha256(body: ByteArray): ByteArray = MessageDigest.getInstance(ALGORITHM).digest(body)
}
