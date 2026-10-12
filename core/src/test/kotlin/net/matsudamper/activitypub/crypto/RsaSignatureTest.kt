package net.matsudamper.activitypub.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// SHA256withRSA での署名と検証を確認する。
// RsaKeysTest と同じく nativeTest でも実行され、
// Phase 2 の HTTP Signatures が native-image 上で成立することの確認を兼ねる。
class RsaSignatureTest {
    private val data = "(request-target): post /users/feed/inbox".toByteArray()

    @Test
    fun `自分の鍵で署名して検証できる`() {
        val keyPair = RsaKeys.generateKeyPair()

        val signature = RsaSignature.sign(keyPair.private, data)

        assertTrue(RsaSignature.verify(keyPair.public, data, signature))
    }

    @Test
    fun `PEM を経由した鍵でも検証できる`() {
        // 実際の運用では秘密鍵も相手の公開鍵も PEM の文字列から復元する
        val keyPair = RsaKeys.generateKeyPair()
        val privateKey = RsaKeys.decodePrivateKeyPem(RsaKeys.encodeToPem(keyPair.private))
        val publicKey = RsaKeys.decodePublicKeyPem(RsaKeys.encodeToPem(keyPair.public))

        val signature = RsaSignature.sign(privateKey, data)

        assertTrue(RsaSignature.verify(publicKey, data, signature))
    }

    @Test
    fun `別の鍵の公開鍵では検証に失敗する`() {
        val signer = RsaKeys.generateKeyPair()
        val other = RsaKeys.generateKeyPair()

        val signature = RsaSignature.sign(signer.private, data)

        assertFalse(RsaSignature.verify(other.public, data, signature))
    }

    @Test
    fun `署名対象が変わると検証に失敗する`() {
        val keyPair = RsaKeys.generateKeyPair()

        val signature = RsaSignature.sign(keyPair.private, data)

        assertFalse(
            RsaSignature.verify(keyPair.public, "(request-target): post /users/other/inbox".toByteArray(), signature),
        )
    }

    @Test
    fun `壊れた署名を渡しても例外にならず失敗として扱われる`() {
        // inbox には誰でも POST できるので、不正な署名で例外を投げさせない
        val keyPair = RsaKeys.generateKeyPair()

        assertFalse(RsaSignature.verify(keyPair.public, data, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `空の署名を渡しても失敗として扱われる`() {
        val keyPair = RsaKeys.generateKeyPair()

        assertFalse(RsaSignature.verify(keyPair.public, data, ByteArray(0)))
    }
}
