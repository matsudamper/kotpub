package net.matsudamper.activitypub.httpsignature

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Digest を検証しない限り、署名が通ってもボディは差し替えられる。
class BodyDigestTest {
    private val body = """{"type":"Follow"}""".toByteArray()

    @Test
    fun `作った値がそのまま一致する`() {
        assertTrue(BodyDigest.matches(BodyDigest.of(body), body))
    }

    @Test
    fun `既知の値と一致する`() {
        // echo -n '{"type":"Follow"}' | openssl dgst -sha256 -binary | base64
        assertEquals("SHA-256=GYwYnH3BiO6aICFt0ThC5bUIJ4byvqdpWtR8m5fNkww=", BodyDigest.of(body))
    }

    @Test
    fun `ボディが1バイトでも違えば一致しない`() {
        assertFalse(BodyDigest.matches(BodyDigest.of(body), """{"type":"Follow" }""".toByteArray()))
    }

    @Test
    fun `アルゴリズム名の大文字小文字は問わない`() {
        val value = BodyDigest.of(body).replace("SHA-256", "sha-256")

        assertTrue(BodyDigest.matches(value, body))
    }

    @Test
    fun `複数並んでいても SHA-256 を見る`() {
        val value = "SHA-1=2jmj7l5rSw0yVb/vlWAYkK/YBwk=, ${BodyDigest.of(body)}"

        assertTrue(BodyDigest.matches(value, body))
    }

    @Test
    fun `SHA-256 が入っていなければ一致しない`() {
        // SHA-1 しか無いものを通すと弱い方に合わせることになる
        assertFalse(BodyDigest.matches("SHA-1=2jmj7l5rSw0yVb/vlWAYkK/YBwk=", body))
    }

    @Test
    fun `Base64 として壊れていても例外にしない`() {
        assertFalse(BodyDigest.matches("SHA-256=!!!", body))
    }

    @Test
    fun `区切りが無ければ一致しない`() {
        assertFalse(BodyDigest.matches("SHA-256", body))
    }
}
