package net.matsudamper.activitypub.httpsignature

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Signature ヘッダは相手の実装が作った文字列。壊れていても例外にせず null にする。
class SignatureHeaderTest {
    private val signature = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

    @Test
    fun `Mastodon が送ってくる形を読める`() {
        val header =
            SignatureHeader.parse(
                """keyId="https://remote.example/users/alice#main-key",algorithm="rsa-sha256",""" +
                    """headers="(request-target) host date digest",signature="$signature"""",
            )

        assertEquals("https://remote.example/users/alice#main-key", header?.keyId)
        assertEquals("rsa-sha256", header?.algorithm)
        assertEquals(listOf("(request-target)", "host", "date", "digest"), header?.headers)
        assertContentEquals(byteArrayOf(1, 2, 3), header?.signature)
    }

    @Test
    fun `パラメータ名の大文字小文字を問わない`() {
        val header = SignatureHeader.parse("""KeyId="k",Signature="$signature"""")

        assertEquals("k", header?.keyId)
    }

    @Test
    fun `ヘッダ名は小文字に揃える`() {
        val header = SignatureHeader.parse("""keyId="k",headers="(request-target) Host Date",signature="$signature"""")

        assertEquals(listOf("(request-target)", "host", "date"), header?.headers)
    }

    @Test
    fun `カンマの後の空白を許す`() {
        val header = SignatureHeader.parse("""keyId="k", algorithm="rsa-sha256", signature="$signature"""")

        assertEquals("k", header?.keyId)
        assertEquals("rsa-sha256", header?.algorithm)
    }

    @Test
    fun `引用符が無くても読める`() {
        val header = SignatureHeader.parse("""keyId=k,signature=$signature""")

        assertEquals("k", header?.keyId)
    }

    @Test
    fun `headers が無ければ date だけを署名対象とみなす`() {
        // draft-cavage-http-signatures の既定値。勝手に広げると
        // 署名されていないヘッダを署名済みとして扱うことになる
        val header = SignatureHeader.parse("""keyId="k",signature="$signature"""")

        assertEquals(listOf("date"), header?.headers)
    }

    @Test
    fun `keyId が無ければ読めない`() {
        assertNull(SignatureHeader.parse("""algorithm="rsa-sha256",signature="$signature""""))
    }

    @Test
    fun `signature が無ければ読めない`() {
        assertNull(SignatureHeader.parse("""keyId="k",algorithm="rsa-sha256""""))
    }

    @Test
    fun `signature が Base64 でなければ読めない`() {
        assertNull(SignatureHeader.parse("""keyId="k",signature="!!!not base64!!!""""))
    }

    @Test
    fun `引用符が閉じていなければ読めない`() {
        // どこまでが値か決められないので、部分的に解釈せず全部捨てる
        assertNull(SignatureHeader.parse("""keyId="k,signature="$signature""""))
    }

    @Test
    fun `同じ名前が2回来たら先に出た方を採る`() {
        // 後から上書きできると、検証に使う鍵を差し替える余地が生まれる
        val header = SignatureHeader.parse("""keyId="first",keyId="second",signature="$signature"""")

        assertEquals("first", header?.keyId)
    }

    @Test
    fun `空文字は読めない`() {
        assertNull(SignatureHeader.parse(""))
    }
}
