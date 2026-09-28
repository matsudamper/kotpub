package net.matsudamper.activitypub.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * ActivityPub のアクターが持つ RSA 鍵の生成と PEM 変換。
 *
 * Mastodon は Actor JSON の `publicKey.publicKeyPem` を X.509 SubjectPublicKeyInfo の
 * PEM として読む。そのため公開鍵は `BEGIN PUBLIC KEY`、秘密鍵は PKCS#8 の
 * `BEGIN PRIVATE KEY` で扱う。OpenSSL が古い形式で出す `BEGIN RSA PUBLIC KEY`（PKCS#1）
 * ではないので注意する。
 */
object RsaKeys {
    /**
     * 鍵長。ActivityPub の実装は 2048bit を前提にしていることが多い。
     * これより短いと相手に拒否されることがある。
     */
    const val KEY_SIZE_BITS = 2048

    private const val ALGORITHM = "RSA"
    private const val PRIVATE_KEY_LABEL = "PRIVATE KEY"
    private const val PUBLIC_KEY_LABEL = "PUBLIC KEY"

    /** PEM の本文は 64 文字ごとに折り返す（RFC 7468） */
    private const val PEM_LINE_LENGTH = 64

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(ALGORITHM)
        generator.initialize(KEY_SIZE_BITS)
        return generator.generateKeyPair()
    }

    /**
     * 秘密鍵から公開鍵を組み立てる。
     *
     * PKCS#8 の RSA 秘密鍵は modulus と publicExponent を持っている（[RSAPrivateCrtKey]）ので、
     * 公開鍵は秘密鍵だけから導ける。2 つのファイルを保存して片方だけ差し替わる事故を
     * 避けるため、保存するのは秘密鍵だけにして公開鍵は起動のたびにここで作る。
     */
    fun derivePublicKey(privateKey: PrivateKey): PublicKey {
        // PKCS#8 で読めば通常こちらになる。CRT の情報を持たない鍵だと modulus しか取れず導けない
        require(privateKey is RSAPrivateCrtKey) {
            "公開鍵を導けない秘密鍵: ${privateKey.javaClass.name}"
        }
        val spec = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
        return KeyFactory.getInstance(ALGORITHM).generatePublic(spec)
    }

    fun encodeToPem(privateKey: PrivateKey): String {
        // JCA の実装によっては別形式を返しうるので、PEM のラベルと中身がずれないよう確かめる
        check(privateKey.format == "PKCS#8") {
            "秘密鍵が PKCS#8 ではない: ${privateKey.format}"
        }
        return toPem(PRIVATE_KEY_LABEL, privateKey.encoded)
    }

    fun encodeToPem(publicKey: PublicKey): String {
        check(publicKey.format == "X.509") {
            "公開鍵が X.509 ではない: ${publicKey.format}"
        }
        return toPem(PUBLIC_KEY_LABEL, publicKey.encoded)
    }

    fun decodePrivateKeyPem(pem: String): PrivateKey {
        val der = fromPem(PRIVATE_KEY_LABEL, pem)
        return KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(der))
    }

    fun decodePublicKeyPem(pem: String): PublicKey {
        val der = fromPem(PUBLIC_KEY_LABEL, pem)
        return KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(der))
    }

    private fun toPem(
        label: String,
        der: ByteArray,
    ): String {
        val body = Base64.getMimeEncoder(PEM_LINE_LENGTH, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    /**
     * PEM からヘッダとフッタに挟まれた部分を取り出して Base64 デコードする。
     *
     * 改行コードや前後の余分な行に左右されないよう、位置で切り出してから
     * 空白を落とす。相手から受け取った PEM は CRLF のことも末尾改行が無いこともある。
     */
    private fun fromPem(
        label: String,
        pem: String,
    ): ByteArray {
        val header = "-----BEGIN $label-----"
        val footer = "-----END $label-----"

        val headerIndex = pem.indexOf(header)
        require(headerIndex >= 0) { "PEM に $header が無い" }

        val bodyStart = headerIndex + header.length
        val footerIndex = pem.indexOf(footer, startIndex = bodyStart)
        require(footerIndex >= 0) { "PEM に $footer が無い" }

        val body = pem.substring(bodyStart, footerIndex).filterNot { it.isWhitespace() }
        return Base64.getDecoder().decode(body)
    }
}
