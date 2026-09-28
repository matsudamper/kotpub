package net.matsudamper.activitypub.httpsignature

import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinDuration
import net.matsudamper.activitypub.crypto.RsaSignature

/**
 * 受信したリクエストの HTTP Signatures を検証する。
 *
 * ActivityPub のサーバー間通信に認証はこれしか無い。ここを通ったかどうかが
 * 「その相手が本当にそのアクターか」の唯一の根拠になるので、判断が曖昧なものは
 * 通さずに落とす。
 *
 * 検証の順番は安いものから。ヘッダの形 → 時刻 → ボディのダイジェスト →
 * 鍵の取得（ここだけ外に HTTP を投げる）→ 署名の検証、とすることで、
 * 明らかにおかしいリクエストで相手のサーバーを引きに行かずに済む。
 *
 * @param clock テストから時刻を固定するために持つ
 */
class HttpSignatureVerifier(
    private val publicKeys: PublicKeys,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 署名の鍵の場所。検証はしないので、書かれている値が本物とは限らない。
     *
     * 鍵を取りに行く前に、相手を見て断るために使う
     */
    fun claimedKeyId(request: SignedRequest): String? =
        request.headers[SIGNATURE_HEADER]?.let { SignatureHeader.parse(it) }?.keyId

    suspend fun verify(request: SignedRequest): HttpSignatureResult {
        val rawHeader =
            request.headers[SIGNATURE_HEADER]
                ?: return HttpSignatureResult.Rejected("Signature ヘッダが無い")

        val signature =
            SignatureHeader.parse(rawHeader)
                ?: return HttpSignatureResult.Rejected("Signature ヘッダを読めない: $rawHeader")

        // algorithm は省略可。書いてあるのに対応外なら、こちらの検証方法と食い違っている
        val algorithm = signature.algorithm
        if (algorithm != null && algorithm !in SUPPORTED_ALGORITHMS) {
            return HttpSignatureResult.Rejected("対応していない algorithm: $algorithm")
        }

        // 署名対象に入っていないヘッダは、通っても中身を信用できない。
        // 宛先 (request-target, host)、時刻 (date)、ボディ (digest) は必須にする
        val required =
            buildList {
                add(SigningString.REQUEST_TARGET)
                add("host")
                add("date")
                if (request.body.isNotEmpty()) add("digest")
            }
        val missing = required.filterNot { it in signature.headers }
        if (missing.isNotEmpty()) {
            return HttpSignatureResult.Rejected("署名対象に入っていない: ${missing.joinToString(" ")}")
        }

        val date =
            request.headers[DATE_HEADER]
                ?.let { HttpDate.parse(it) }
                ?: return HttpSignatureResult.Rejected("Date ヘッダを読めない: ${request.headers[DATE_HEADER]}")

        // 署名ごと記録して後から投げ直されるのを防ぐ。時計のずれもここで弾く
        val skew = java.time.Duration.between(date, clock.instant()).abs().toKotlinDuration()
        if (skew > MAX_CLOCK_SKEW) {
            return HttpSignatureResult.Rejected("Date が現在時刻から離れすぎている: ${request.headers[DATE_HEADER]}")
        }

        if (request.body.isNotEmpty()) {
            val digest =
                request.headers[DIGEST_HEADER]
                    ?: return HttpSignatureResult.Rejected("Digest ヘッダが無い")
            if (!BodyDigest.matches(digest, request.body)) {
                return HttpSignatureResult.Rejected("Digest がボディと一致しない: $digest")
            }
        }

        val signingString =
            SigningString.build(request, signature.headers)
                ?: return HttpSignatureResult.Rejected("署名対象のヘッダが揃っていない: ${signature.headers.joinToString(" ")}")

        val key =
            when (val lookup = publicKeys.find(signature.keyId)) {
                is PublicKeyLookup.Found -> lookup.key

                PublicKeyLookup.Gone ->
                    return HttpSignatureResult.Rejected("アクターが消えていて公開鍵を取れない: ${signature.keyId}")

                PublicKeyLookup.Unavailable ->
                    return HttpSignatureResult.Rejected("公開鍵を取得できない: ${signature.keyId}")
            }

        if (matches(signature, signingString, key)) {
            return HttpSignatureResult.Verified(keyId = key.keyId, owner = key.owner)
        }

        // 相手が鍵を替えていることがある。替わったことは、こちらが覚えている鍵では
        // 通らない署名が届いて初めて分かるので、ここで 1 回だけ引き直して試す。
        // 引き直さないと、覚えている鍵を捨てるまでの間その相手を拒み続ける
        val refreshed =
            (publicKeys.refresh(signature.keyId) as? PublicKeyLookup.Found)?.key
                ?: return HttpSignatureResult.Rejected("署名が一致しない: ${signature.keyId}")

        if (!matches(signature, signingString, refreshed)) {
            return HttpSignatureResult.Rejected("引き直した鍵でも署名が一致しない: ${signature.keyId}")
        }

        return HttpSignatureResult.Verified(keyId = refreshed.keyId, owner = refreshed.owner)
    }

    private fun matches(
        signature: SignatureHeader,
        signingString: String,
        key: SignatureKey,
    ): Boolean =
        RsaSignature.verify(
            publicKey = key.publicKey,
            data = signingString.toByteArray(Charsets.UTF_8),
            signature = signature.signature,
        )

    private companion object {
        const val SIGNATURE_HEADER = "Signature"
        const val DIGEST_HEADER = "Digest"
        const val DATE_HEADER = "Date"

        /**
         * `hs2019` は新しい draft での呼び名で、RSA 鍵に対しては中身が rsa-sha256 になる。
         * 送ってくる実装があるので受ける。
         */
        val SUPPORTED_ALGORITHMS = setOf("rsa-sha256", "hs2019")

        /**
         * 許す時計のずれ。
         *
         * 短くしすぎると、相手のサーバーの時計が少し進んでいるだけで
         * フォローが成立しなくなる。長くすると投げ直しを受け入れる窓が広がる。
         * draft が例に挙げている範囲で、両サーバーの NTP のずれを吸収できる幅にする。
         */
        val MAX_CLOCK_SKEW: Duration = 5.minutes
    }
}

/** [HttpSignatureVerifier.verify] の結果 */
sealed interface HttpSignatureResult {
    /**
     * 検証に通った。
     *
     * @param owner 署名した相手のアクター id。これ以降、リクエストの送り主は
     *   このアクターだとして扱ってよい
     */
    data class Verified(
        val keyId: String,
        val owner: String,
    ) : HttpSignatureResult

    /**
     * 通らなかった。
     *
     * @param reason ログに出す理由。相手には返さない。
     *   どこで落ちたかを返すと、通る形を総当たりで探す助けになる
     */
    data class Rejected(
        val reason: String,
    ) : HttpSignatureResult
}
