package net.matsudamper.activitypub.httpsignature

import java.security.PublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.matsudamper.activitypub.TestRemoteActor
import net.matsudamper.activitypub.TestRemoteActors
import net.matsudamper.activitypub.crypto.RsaKeys
import net.matsudamper.activitypub.http.RequestHeaders

// ActivityPub のサーバー間通信で相手を確かめる手段はこれしか無い。
// 判断が曖昧なものは通さない。
class HttpSignatureVerifierTest {
    private val now: Instant = Instant.parse("2026-08-09T03:00:00Z")
    private val target = "/users/admin/inbox"
    private val host = "example.com"
    private val body = """{"type":"Follow","actor":"${TestRemoteActor.ACTOR_ID}"}""".toByteArray()

    private fun verifier(
        publicKeys: PublicKeys = TestRemoteActor.remoteActors(),
        at: Instant = now,
    ) = HttpSignatureVerifier(publicKeys, Clock.fixed(at, ZoneOffset.UTC))

    private fun request(
        headers: Map<String, String>,
        body: ByteArray = this.body,
        requestTarget: String = target,
    ) = SignedRequest(
        method = "POST",
        requestTarget = requestTarget,
        headers = RequestHeaders.ofSingleValues(headers),
        body = body,
    )

    private fun signedHeaders(
        date: Instant = now,
        requestTarget: String = target,
        headerNames: List<String> = TestSigning.DEFAULT_HEADER_NAMES,
        keyId: String = TestRemoteActor.KEY_ID,
        body: ByteArray = this.body,
    ) = TestSigning.headers(
        requestTarget = requestTarget,
        host = host,
        date = date,
        body = body,
        keyId = keyId,
        headerNames = headerNames,
    )

    @Test
    fun `正しく署名されていれば通る`() =
        runBlocking {
            val result = verifier().verify(request(signedHeaders()))

            val verified = assertIs<HttpSignatureResult.Verified>(result)
            assertEquals(TestRemoteActor.KEY_ID, verified.keyId)
            assertEquals(TestRemoteActor.ACTOR_ID, verified.owner)
        }

    @Test
    fun `Signature ヘッダが無ければ通らない`() =
        runBlocking {
            val headers = signedHeaders().filterKeys { it != "Signature" }

            val result = verifier().verify(request(headers))

            assertIs<HttpSignatureResult.Rejected>(result)
        }

    @Test
    fun `ボディを差し替えたら通らない`() =
        runBlocking {
            // 署名はヘッダにしか掛かっていない。Digest を見ないとここが素通りする
            val result = verifier().verify(request(signedHeaders(), body = """{"type":"Undo"}""".toByteArray()))

            val rejected = assertIs<HttpSignatureResult.Rejected>(result)
            assertTrue(rejected.reason.contains("Digest"))
        }

    @Test
    fun `パスを差し替えたら通らない`() =
        runBlocking {
            val result = verifier().verify(request(signedHeaders(), requestTarget = "/users/other/inbox"))

            assertIs<HttpSignatureResult.Rejected>(result)
        }

    @Test
    fun `Date が離れすぎていたら通らない`() =
        runBlocking {
            // 署名ごと記録して後から投げ直されるのを防ぐ
            val old = now.minus(Duration.ofHours(1))

            val result = verifier().verify(request(signedHeaders(date = old)))

            val rejected = assertIs<HttpSignatureResult.Rejected>(result)
            assertTrue(rejected.reason.contains("Date"))
        }

    @Test
    fun `相手の時計が少し進んでいても通る`() =
        runBlocking {
            // 数分のずれで弾くと、NTP のずれだけでフォローが成立しなくなる
            val result = verifier().verify(request(signedHeaders(date = now.plus(Duration.ofMinutes(1)))))

            assertIs<HttpSignatureResult.Verified>(result)
        }

    @Test
    fun `Digest が署名対象に入っていなければ通らない`() =
        runBlocking {
            // Digest が署名されていなければ、ヘッダごと差し替えてボディを入れ替えられる
            val headerNames = listOf(SigningString.REQUEST_TARGET, "host", "date")

            val result = verifier().verify(request(signedHeaders(headerNames = headerNames)))

            val rejected = assertIs<HttpSignatureResult.Rejected>(result)
            assertTrue(rejected.reason.contains("digest"))
        }

    @Test
    fun `request-target が署名対象に入っていなければ通らない`() =
        runBlocking {
            val headerNames = listOf("host", "date", "digest")

            val result = verifier().verify(request(signedHeaders(headerNames = headerNames)))

            assertIs<HttpSignatureResult.Rejected>(result)
        }

    @Test
    fun `知らない keyId なら通らない`() =
        runBlocking {
            val result = verifier(TestRemoteActors()).verify(request(signedHeaders()))

            val rejected = assertIs<HttpSignatureResult.Rejected>(result)
            assertTrue(rejected.reason.contains("公開鍵"))
        }

    @Test
    fun `別の鍵で署名されていたら通らない`() =
        runBlocking {
            // keyId は本人のものを名乗りつつ、署名だけ手元の鍵で作った場合
            val headers =
                TestSigning.headers(
                    privateKey = RsaKeys.generateKeyPair().private,
                    requestTarget = target,
                    host = host,
                    date = now,
                    body = body,
                )

            val result = verifier().verify(request(headers))

            val rejected = assertIs<HttpSignatureResult.Rejected>(result)
            assertTrue(rejected.reason.contains("署名が一致しない"))
        }

    @Test
    fun `対応していない algorithm なら通らない`() =
        runBlocking {
            val headers =
                signedHeaders().mapValues { (name, value) ->
                    if (name == "Signature") value.replace("rsa-sha256", "hmac-sha256") else value
                }

            val result = verifier().verify(request(headers))

            val rejected = assertIs<HttpSignatureResult.Rejected>(result)
            assertTrue(rejected.reason.contains("algorithm"))
        }

    @Test
    fun `覚えている鍵が古ければ引き直して通す`() =
        runBlocking {
            // 相手が鍵を替えると、替わったことは通らない署名が届いて初めて分かる
            val publicKeys = RotatingPublicKeys(before = RsaKeys.generateKeyPair().public, after = TestRemoteActor.keyPair.public)

            val result = verifier(publicKeys).verify(request(signedHeaders()))

            assertIs<HttpSignatureResult.Verified>(result)
            assertEquals(1, publicKeys.refreshCallCount)
        }

    @Test
    fun `引き直しても合わなければ通らない`() =
        runBlocking {
            val publicKeys = RotatingPublicKeys(before = RsaKeys.generateKeyPair().public, after = RsaKeys.generateKeyPair().public)

            val result = verifier(publicKeys).verify(request(signedHeaders()))

            val rejected = assertIs<HttpSignatureResult.Rejected>(result)
            assertTrue(rejected.reason.contains("引き直した鍵でも署名が一致しない"))
        }

    @Test
    fun `署名が通れば引き直しには行かない`() =
        runBlocking {
            val publicKeys = TestRemoteActor.remoteActors()

            verifier(publicKeys).verify(request(signedHeaders()))

            assertEquals(0, publicKeys.refreshCallCount)
        }

    @Test
    fun `鍵を引きに行く前に明らかな不備で落とす`() =
        runBlocking {
            // 形が壊れたリクエストのたびに相手のサーバーを引きに行かない
            val publicKeys = TestRemoteActor.remoteActors()
            val headers = signedHeaders(date = now.minus(Duration.ofHours(1)))

            verifier(publicKeys).verify(request(headers))

            assertEquals(0, publicKeys.findCallCount)
        }

    /**
     * 鍵を替えた相手。[find] は替える前の鍵を、[refresh] は替えた後の鍵を返す
     */
    private class RotatingPublicKeys(
        private val before: PublicKey,
        private val after: PublicKey,
    ) : PublicKeys {
        var refreshCallCount: Int = 0
            private set

        override suspend fun find(keyId: String): PublicKeyLookup = found(keyId, before)

        override suspend fun refresh(keyId: String): PublicKeyLookup {
            refreshCallCount++
            return found(keyId, after)
        }

        private fun found(
            keyId: String,
            publicKey: PublicKey,
        ): PublicKeyLookup =
            PublicKeyLookup.Found(
                SignatureKey(keyId = keyId, owner = TestRemoteActor.ACTOR_ID, publicKey = publicKey),
            )
    }
}
