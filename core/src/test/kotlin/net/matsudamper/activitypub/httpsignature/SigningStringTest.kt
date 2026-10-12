package net.matsudamper.activitypub.httpsignature

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.matsudamper.activitypub.http.RequestHeaders

// 送信側が署名した文字列と 1 バイトでも違えば検証は落ちる。
class SigningStringTest {
    private fun request(
        headers: RequestHeaders,
        method: String = "POST",
        requestTarget: String = "/users/admin/inbox",
        body: ByteArray = ByteArray(0),
    ) = SignedRequest(method = method, requestTarget = requestTarget, headers = headers, body = body)

    @Test
    fun `headers の並び順どおりに組み立てる`() {
        val headers =
            RequestHeaders.of(
                mapOf(
                    "Host" to listOf("example.com"),
                    "Date" to listOf("Tue, 20 Apr 2021 02:07:55 GMT"),
                    "Digest" to listOf("SHA-256=xxxx"),
                ),
            )

        val actual =
            SigningString.build(
                request(headers),
                listOf(SigningString.REQUEST_TARGET, "host", "date", "digest"),
            )

        assertEquals(
            "(request-target): post /users/admin/inbox\n" +
                "host: example.com\n" +
                "date: Tue, 20 Apr 2021 02:07:55 GMT\n" +
                "digest: SHA-256=xxxx",
            actual,
        )
    }

    @Test
    fun `メソッドは小文字にする`() {
        val actual =
            SigningString.build(
                request(headers = RequestHeaders.of(mapOf()), method = "POST"),
                listOf(SigningString.REQUEST_TARGET),
            )

        assertEquals("(request-target): post /users/admin/inbox", actual)
    }

    @Test
    fun `クエリを含むパスはそのまま入る`() {
        val actual =
            SigningString.build(
                request(headers = RequestHeaders.of(mapOf()), requestTarget = "/users/admin/inbox?a=1"),
                listOf(SigningString.REQUEST_TARGET),
            )

        assertEquals("(request-target): post /users/admin/inbox?a=1", actual)
    }

    @Test
    fun `ヘッダ名の大文字小文字は問わない`() {
        val actual = SigningString.build(request(RequestHeaders.of(mapOf("HOST" to listOf("example.com")))), listOf("host"))

        assertEquals("host: example.com", actual)
    }

    @Test
    fun `同じヘッダが複数あればカンマで繋ぐ`() {
        val headers = RequestHeaders.of(mapOf("X-Test" to listOf("a", "b")))

        assertEquals("x-test: a, b", SigningString.build(request(headers), listOf("x-test")))
    }

    @Test
    fun `並びにあるヘッダが無ければ組み立てない`() {
        // 空文字で埋めると、送信側が署名した内容と違うものを検証してしまう
        assertNull(SigningString.build(request(RequestHeaders.of(mapOf())), listOf("host")))
    }

    @Test
    fun `対応していない擬似ヘッダがあれば組み立てない`() {
        // (created) は hs2019 用。黙って無視すると署名されていない値を通すことになる
        assertNull(SigningString.build(request(RequestHeaders.of(mapOf())), listOf("(created)")))
    }

    @Test
    fun `並びが空なら組み立てない`() {
        assertNull(SigningString.build(request(RequestHeaders.of(mapOf())), emptyList()))
    }
}
