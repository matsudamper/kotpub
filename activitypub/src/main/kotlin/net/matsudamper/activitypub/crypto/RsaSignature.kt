package net.matsudamper.activitypub.crypto

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException

/**
 * RSA-SHA256 の署名と検証。
 *
 * HTTP Signatures の `algorithm="rsa-sha256"` に対応する。
 * 署名対象のバイト列（署名文字列）の組み立ては Phase 2 で別に用意する。
 */
object RsaSignature {
    private const val ALGORITHM = "SHA256withRSA"

    fun sign(
        privateKey: PrivateKey,
        data: ByteArray,
    ): ByteArray {
        val signature = Signature.getInstance(ALGORITHM)
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    /**
     * 署名を検証する。検証に通らなければ false を返す。
     *
     * 壊れた署名や長さの合わない署名を渡すと JCA は例外を投げるが、
     * 呼び出し側から見れば「検証に失敗した」と同じ扱いでよい。
     * 受信した inbox のリクエストは中身を信用できないので、ここで吸収して
     * 例外がルーティングまで上がらないようにする。
     */
    fun verify(
        publicKey: PublicKey,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean =
        try {
            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(signature)
        } catch (e: SignatureException) {
            false
        }
}
