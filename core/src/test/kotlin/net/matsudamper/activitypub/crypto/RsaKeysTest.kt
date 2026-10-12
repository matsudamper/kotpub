package net.matsudamper.activitypub.crypto

import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// 鍵の生成と PEM の相互変換を確認する。
// このテストは nativeTest でも実行され、JCA が native-image 上で動くことの確認も兼ねる。
// native バイナリで RSA が使えないと Phase 1 のアクター公開鍵も
// Phase 2 の署名も成立しないため、ここで先に落とす。
class RsaKeysTest {
    @Test
    fun `2048bit の鍵ペアを生成する`() {
        val keyPair = RsaKeys.generateKeyPair()

        val publicKey = keyPair.public as RSAPublicKey
        assertEquals(RsaKeys.KEY_SIZE_BITS, publicKey.modulus.bitLength())
        assertEquals("RSA", keyPair.private.algorithm)
    }

    @Test
    fun `生成のたびに違う鍵になる`() {
        val first = RsaKeys.encodeToPem(RsaKeys.generateKeyPair().public)
        val second = RsaKeys.encodeToPem(RsaKeys.generateKeyPair().public)

        assertTrue(first != second, "2 回続けて同じ鍵が出た")
    }

    @Test
    fun `秘密鍵を PEM にして読み戻すと同じ鍵になる`() {
        val original = RsaKeys.generateKeyPair().private

        val restored = RsaKeys.decodePrivateKeyPem(RsaKeys.encodeToPem(original))

        assertEquals(original, restored)
    }

    @Test
    fun `公開鍵を PEM にして読み戻すと同じ鍵になる`() {
        val original = RsaKeys.generateKeyPair().public

        val restored = RsaKeys.decodePublicKeyPem(RsaKeys.encodeToPem(original))

        assertEquals(original, restored)
    }

    @Test
    fun `公開鍵の PEM は BEGIN PUBLIC KEY で始まる`() {
        // Mastodon が読むのは PKCS#1 の BEGIN RSA PUBLIC KEY ではなく
        // X_509 SubjectPublicKeyInfo なので、ラベルを取り違えていないか確かめる
        val pem = RsaKeys.encodeToPem(RsaKeys.generateKeyPair().public)

        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n"), "先頭が違う: $pem")
        assertTrue(pem.endsWith("-----END PUBLIC KEY-----\n"), "末尾が違う: $pem")
    }

    @Test
    fun `秘密鍵の PEM は BEGIN PRIVATE KEY で始まる`() {
        val pem = RsaKeys.encodeToPem(RsaKeys.generateKeyPair().private)

        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----\n"), "先頭が違う: $pem")
        assertTrue(pem.endsWith("-----END PRIVATE KEY-----\n"), "末尾が違う: $pem")
    }

    @Test
    fun `PEM の本文は 64 文字ごとに折り返される`() {
        val pem = RsaKeys.encodeToPem(RsaKeys.generateKeyPair().public)

        val bodyLines = pem.lines().filter { it.isNotEmpty() && !it.startsWith("-----") }
        assertTrue(bodyLines.size > 1, "折り返されていない: $pem")
        // 最終行だけは 64 文字未満になりうる
        assertTrue(
            bodyLines.dropLast(1).all { it.length == 64 },
            "64 文字で折り返されていない: ${bodyLines.map { it.length }}",
        )
        assertTrue(bodyLines.last().length <= 64, "最終行が長すぎる: ${bodyLines.last().length}")
    }

    @Test
    fun `CRLF の PEM も読める`() {
        // 相手から受け取る PEM の改行コードは選べない
        val pem = RsaKeys.encodeToPem(RsaKeys.generateKeyPair().public)

        val restored = RsaKeys.decodePublicKeyPem(pem.replace("\n", "\r\n"))

        assertEquals(RsaKeys.decodePublicKeyPem(pem), restored)
    }

    @Test
    fun `前後に余計な行があっても読める`() {
        val pem = RsaKeys.encodeToPem(RsaKeys.generateKeyPair().public)

        val restored = RsaKeys.decodePublicKeyPem("Bag Attributes\n$pem\n")

        assertEquals(RsaKeys.decodePublicKeyPem(pem), restored)
    }

    @Test
    fun `秘密鍵から公開鍵を導ける`() {
        val keyPair = RsaKeys.generateKeyPair()

        val derived = RsaKeys.derivePublicKey(keyPair.private)

        assertEquals(keyPair.public, derived)
        assertEquals(RsaKeys.encodeToPem(keyPair.public), RsaKeys.encodeToPem(derived))
    }

    @Test
    fun `PEM から読み戻した秘密鍵からも公開鍵を導ける`() {
        val keyPair = RsaKeys.generateKeyPair()
        val restored = RsaKeys.decodePrivateKeyPem(RsaKeys.encodeToPem(keyPair.private))

        assertEquals(keyPair.public, RsaKeys.derivePublicKey(restored))
    }

    @Test
    fun `ラベルが合わない PEM は例外になる`() {
        val privateKeyPem = RsaKeys.encodeToPem(RsaKeys.generateKeyPair().private)

        assertFailsWith<IllegalArgumentException> {
            RsaKeys.decodePublicKeyPem(privateKeyPem)
        }
    }

    @Test
    fun `PEM でない文字列は例外になる`() {
        assertFailsWith<IllegalArgumentException> {
            RsaKeys.decodePublicKeyPem("公開鍵ではない")
        }
    }
}
