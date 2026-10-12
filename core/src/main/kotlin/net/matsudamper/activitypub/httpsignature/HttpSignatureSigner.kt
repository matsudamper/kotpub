package net.matsudamper.activitypub.httpsignature

import java.security.PrivateKey
import java.time.Clock
import java.util.Base64
import net.matsudamper.activitypub.crypto.RsaSignature
import net.matsudamper.activitypub.http.RequestHeaders

/**
 * こちらから送るリクエストに HTTP Signatures を付ける。
 *
 * [HttpSignatureVerifier] の逆で、署名文字列の組み立ては [SigningString] を共有する。
 * 相手のサーバーは、こちらが署名した文字列を同じ手順で組み直して検証するので、
 * 1 バイトでも食い違えば通らない。
 *
 * 署名対象は `(request-target)` `host` `date` と、ボディがあれば `digest` の 4 つ。
 * Mastodon が送ってくる並びと同じにしてある。`digest` を外すとボディを
 * 差し替えられるので、ボディがあるときは必ず入れる。
 *
 * @param clock テストから時刻を固定するために持つ
 */
class HttpSignatureSigner(
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 署名済みのヘッダを作る。返すのは `Host` `Date` `Digest` `Signature` だけで、
     * `Content-Type` のような中身に関わるヘッダは呼び出し側が付ける。
     *
     * @param keyId 相手がこちらの公開鍵を取りに来る URL。`<actor>#main-key` の形
     * @param requestTarget パスとクエリ。実際に送るリクエストラインと同じ綴りにすること
     * @param host `Host` ヘッダに載る値。既定ポートならポート番号は付けない
     */
    fun sign(
        keyId: String,
        privateKey: PrivateKey,
        method: String,
        requestTarget: String,
        host: String,
        body: ByteArray,
    ): Map<String, String> {
        val signed =
            buildMap {
                put("Host", host)
                put("Date", HttpDate.format(clock.instant()))
                if (body.isNotEmpty()) put("Digest", BodyDigest.of(body))
            }

        val headerNames =
            buildList {
                add(SigningString.REQUEST_TARGET)
                add("host")
                add("date")
                if (body.isNotEmpty()) add("digest")
            }

        val request =
            SignedRequest(
                method = method,
                requestTarget = requestTarget,
                headers = RequestHeaders.ofSingleValues(signed),
                body = body,
            )

        // 署名対象のヘッダはすぐ上で自分が作ったものなので、組み立てに失敗するのは
        // このクラスの不具合でしかない。null を握り潰すと署名の無いリクエストが
        // 飛んでいって、相手側の 401 として初めて気付くことになる
        val signingString =
            requireNotNull(SigningString.build(request, headerNames)) {
                "署名文字列を組み立てられない: ${headerNames.joinToString(" ")}"
            }

        val signature =
            Base64.getEncoder().encodeToString(
                RsaSignature.sign(privateKey, signingString.toByteArray(Charsets.UTF_8)),
            )

        return signed +
            mapOf(
                "Signature" to
                    """keyId="$keyId",algorithm="$ALGORITHM",""" +
                    """headers="${headerNames.joinToString(" ")}",signature="$signature"""",
            )
    }

    private companion object {
        /**
         * `hs2019` の方が新しい draft での呼び名だが、対応していない実装がまだあるので
         * 受け取る側の実装が広い `rsa-sha256` で送る。
         */
        const val ALGORITHM = "rsa-sha256"
    }
}
