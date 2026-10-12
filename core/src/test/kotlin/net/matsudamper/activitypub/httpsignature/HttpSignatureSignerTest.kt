package net.matsudamper.activitypub.httpsignature

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.matsudamper.activitypub.TestActorKey
import net.matsudamper.activitypub.TestRemoteActors
import net.matsudamper.activitypub.http.RequestHeaders

// こちらから送るリクエストに署名を付ける側。
// 相手のサーバーは受け取った署名文字列を組み直して検証するので、
// 通ったかどうかは検証器に通してみるのが確実。
class HttpSignatureSignerTest {
    private val actorId = "https://example.com/users/admin"
    private val keyId = "$actorId#main-key"

    private val verifier =
        HttpSignatureVerifier(
            TestRemoteActors.of(keyId = keyId, owner = actorId, publicKey = TestActorKey.value.publicKey),
        )

    private fun sign(
        requestTarget: String = "/users/alice/inbox",
        host: String = "remote.example",
        body: ByteArray = """{"type":"Accept"}""".toByteArray(),
    ): Map<String, String> =
        HttpSignatureSigner().sign(
            keyId = keyId,
            privateKey = TestActorKey.value.privateKey,
            method = "POST",
            requestTarget = requestTarget,
            host = host,
            body = body,
        )

    private fun verify(
        headers: Map<String, String>,
        requestTarget: String = "/users/alice/inbox",
        body: ByteArray = """{"type":"Accept"}""".toByteArray(),
    ): HttpSignatureResult =
        runBlocking {
            verifier.verify(
                SignedRequest(
                    method = "POST",
                    requestTarget = requestTarget,
                    headers = RequestHeaders.ofSingleValues(headers),
                    body = body,
                ),
            )
        }

    @Test
    fun `署名したリクエストは検証を通る`() {
        val result = verify(sign())

        val verified = assertIs<HttpSignatureResult.Verified>(result)
        assertEquals(actorId, verified.owner)
    }

    @Test
    fun `ボディが差し替わると検証に落ちる`() {
        // 署名が掛かるのはヘッダだけ。Digest を経由してボディの差し替えが分かる
        val result = verify(sign(), body = """{"type":"Reject"}""".toByteArray())

        assertIs<HttpSignatureResult.Rejected>(result)
    }

    @Test
    fun `パスが違えば検証に落ちる`() {
        val result = verify(sign(requestTarget = "/users/alice/inbox"), requestTarget = "/users/bob/inbox")

        assertIs<HttpSignatureResult.Rejected>(result)
    }

    @Test
    fun `署名対象に request-target と host と date と digest が入る`() {
        val signature = SignatureHeader.parse(sign().getValue("Signature"))

        assertEquals(
            listOf("(request-target)", "host", "date", "digest"),
            signature?.headers,
        )
    }

    @Test
    fun `Date は GMT の綴りで入る`() {
        val date = sign().getValue("Date")

        // RFC 1123 のパーサは Z を受け付けない。相手が読める綴りであることを見る
        assertTrue(date.endsWith(" GMT"), date)
        assertTrue(HttpDate.parse(date) != null, date)
    }

    @Test
    fun `ボディが無ければ digest を付けない`() {
        val headers = sign(body = ByteArray(0))

        assertFalse(headers.containsKey("Digest"))
        assertEquals(
            listOf("(request-target)", "host", "date"),
            SignatureHeader.parse(headers.getValue("Signature"))?.headers,
        )
    }
}
