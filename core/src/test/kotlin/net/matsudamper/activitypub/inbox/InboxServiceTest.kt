package net.matsudamper.activitypub.inbox

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.matsudamper.activitypub.FakeFavouriteStore
import net.matsudamper.activitypub.FakeFollowerStore
import net.matsudamper.activitypub.FakeInboxDomainBlocks
import net.matsudamper.activitypub.FakeStampStore
import net.matsudamper.activitypub.TestRemoteActor
import net.matsudamper.activitypub.TestRemoteActors
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.actor.RemoteActor
import net.matsudamper.activitypub.actor.RemoteActors
import net.matsudamper.activitypub.crypto.RsaKeys
import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.favourite.FavouriteStore.ReceivedFavourite
import net.matsudamper.activitypub.http.RequestHeaders
import net.matsudamper.activitypub.httpsignature.HttpSignatureVerifier
import net.matsudamper.activitypub.httpsignature.PublicKeyLookup
import net.matsudamper.activitypub.httpsignature.PublicKeys
import net.matsudamper.activitypub.httpsignature.SignedRequest
import net.matsudamper.activitypub.httpsignature.TestSigning

// 受け取ったものを信用してよいかの判断と、種類ごとの振り分け。
// HTTP の status への変換は inboxRoutes 側なので、ここはサーバーを立てずに確かめる。
class InboxServiceTest {
    private val recipientUrls = ActorUrls(domain = "example.com", username = "admin")

    private val recipient = InboxRecipient.Account(recipientUrls)

    private val host = "example.com"

    private val path = "/users/admin/inbox"

    // 呼ばれたことと、渡ってきたものを記録するだけのハンドラ
    private class RecordingHandler(
        override val type: String,
    ) : InboxActivityHandler {
        val calls: MutableList<Call> = mutableListOf()

        override suspend fun handle(
            recipient: InboxRecipient,
            verifiedSignerActorId: String,
            activity: InboxActivity,
            rawActivityJson: JsonObject,
        ) {
            calls += Call(
                recipient = recipient,
                verifiedSignerActorId = verifiedSignerActorId,
                activity = activity,
                rawActivityJson = rawActivityJson,
            )
        }

        class Call(
            val recipient: InboxRecipient,
            val verifiedSignerActorId: String,
            val activity: InboxActivity,
            val rawActivityJson: JsonObject,
        )
    }

    private fun follow(
        actor: String = TestRemoteActor.ACTOR_ID,
        target: String = recipientUrls.actorId,
    ): ByteArray =
        """
        {"id":"https://remote.example/activities/1","type":"Follow",
         "actor":"$actor","object":"$target"}
        """.trimIndent().toByteArray()

    private fun delete(actor: String = TestRemoteActor.ACTOR_ID): ByteArray =
        """
        {"id":"https://remote.example/activities/4","type":"Delete",
         "actor":"$actor","object":"$actor"}
        """.trimIndent().toByteArray()

    private fun undo(actor: String = TestRemoteActor.ACTOR_ID): ByteArray =
        """
        {"id":"https://remote.example/activities/2","type":"Undo",
         "actor":"$actor","object":"https://remote.example/activities/1"}
        """.trimIndent().toByteArray()

    /**
     * 相手のサーバーが送ってくる形の、署名済みリクエストを組む。
     *
     * @param signedBody 署名を作る対象。[body] と別のものを渡すと、
     *   送信後にボディを差し替えられた形になる
     */
    private fun signedRequest(
        body: ByteArray,
        signedBody: ByteArray = body,
    ): SignedRequest {
        val headers =
            TestSigning.headers(
                requestTarget = path,
                host = host,
                date = Instant.now(),
                body = signedBody,
            )

        return SignedRequest(
            method = "POST",
            requestTarget = path,
            headers = RequestHeaders.ofSingleValues(headers),
            body = body,
        )
    }

    private fun service(
        handlers: List<InboxActivityHandler>,
        remoteActors: RemoteActors = TestRemoteActor.remoteActors(),
        publicKeys: PublicKeys = remoteActors,
        domainBlocks: FakeInboxDomainBlocks = FakeInboxDomainBlocks(blockedUrls = setOf()),
    ): InboxService = InboxService(
        verifier = HttpSignatureVerifier(publicKeys),
        domainBlocks = domainBlocks,
        handlers = handlers,
    )

    /**
     * フォローの記録に相手の鍵が残っている状態
     */
    private fun recordedFollower(): FakeFollowerStore =
        FakeFollowerStore().apply {
            record(
                username = recipientUrls.username,
                follower = RemoteActor(
                    actorId = TestRemoteActor.ACTOR_ID,
                    inbox = TestRemoteActor.INBOX,
                    sharedInbox = null,
                    publicKeyPem = RsaKeys.encodeToPem(TestRemoteActor.keyPair.public),
                    profile = TestRemoteActor.noProfile,
                ),
                followActivityUri = "https://remote.example/activities/1",
                receivedAt = Instant.now(),
                acceptBody = "{}",
            )
        }

    @Test
    fun `type が一致するハンドラに渡す`() =
        runBlocking {
            val handler = RecordingHandler("Follow")

            val result = service(listOf(handler)).receive(recipient, signedRequest(follow()))

            assertEquals(InboxResult.Accepted, result)

            val call = handler.calls.single()
            assertEquals(recipient, call.recipient)
            // 渡すのは署名を検証した結果の持ち主で、ボディの自称ではない
            assertEquals(TestRemoteActor.ACTOR_ID, call.verifiedSignerActorId)
            assertEquals("Follow", call.activity.type)
            // Accept に丸ごと入れるので、元の JSON も渡っている必要がある
            assertEquals("https://remote.example/activities/1", call.rawActivityJson["id"]?.jsonPrimitive?.content)
        }

    @Test
    fun `受信を止めているドメインからは鍵を引かずに受け取って捨てる`() =
        runBlocking {
            val handler = RecordingHandler("Follow")
            val domainBlocks = FakeInboxDomainBlocks(blockedUrls = setOf(TestRemoteActor.KEY_ID))
            // 鍵を引きに行くと落ちる。止めている相手のサーバーには取りに行かない
            val publicKeys = object : PublicKeys {
                override suspend fun find(keyId: String): PublicKeyLookup = fail("受信を止めているドメインの鍵を引きに行った")

                override suspend fun refresh(keyId: String): PublicKeyLookup = fail("受信を止めているドメインの鍵を引きに行った")
            }

            val result = service(
                handlers = listOf(handler),
                publicKeys = publicKeys,
                domainBlocks = domainBlocks,
            ).receive(recipient, signedRequest(follow()))

            // 4xx を返すと相手は諦めるまで送り直してくる
            assertEquals(InboxResult.Accepted, result)
            assertTrue(handler.calls.isEmpty(), "${handler.calls.size}")
            assertTrue(domainBlocks.signedRequestSigners.isEmpty())
        }

    @Test
    fun `受信を止めているかを引けなければハンドラに渡さずに受け取って捨てる`() =
        runBlocking {
            val handler = RecordingHandler("Follow")
            val domainBlocks = object : InboxDomainBlocks {
                override fun blocksInboxFrom(url: String): Boolean = throw IllegalStateException("DB がロックされている")

                override fun signedRequestReceived(verifiedSignerActorId: String) = Unit
            }

            val result = InboxService(
                verifier = HttpSignatureVerifier(TestRemoteActor.remoteActors()),
                domainBlocks = domainBlocks,
                handlers = listOf(handler),
            ).receive(recipient, signedRequest(follow()))

            assertEquals(InboxResult.Accepted, result)
            assertTrue(handler.calls.isEmpty(), "${handler.calls.size}")
        }

    @Test
    fun `署名を検証できたら届いたことを記録する`() =
        runBlocking {
            val domainBlocks = FakeInboxDomainBlocks(blockedUrls = setOf())

            service(handlers = listOf(RecordingHandler("Follow")), domainBlocks = domainBlocks)
                .receive(recipient, signedRequest(follow()))

            assertEquals(listOf(TestRemoteActor.ACTOR_ID), domainBlocks.signedRequestSigners)
        }

    @Test
    fun `引き当てられない type は何もせずに受け取る`() =
        runBlocking {
            val handler = RecordingHandler("Follow")

            val result = service(listOf(handler)).receive(recipient, signedRequest(undo()))

            // 未対応のアクティビティに 5xx を返すと相手は同じものを送り直し続ける
            assertEquals(InboxResult.Accepted, result)
            assertTrue(handler.calls.isEmpty(), "${handler.calls.size}")
        }

    @Test
    fun `公開鍵を引けなければ拒否してハンドラを呼ばない`() =
        runBlocking {
            val handler = RecordingHandler("Follow")

            val result =
                service(listOf(handler), remoteActors = TestRemoteActors())
                    .receive(recipient, signedRequest(follow()))

            assertEquals(InboxResult.Unauthorized, result)
            assertTrue(handler.calls.isEmpty(), "${handler.calls.size}")
        }

    @Test
    fun `ボディを差し替えたら拒否する`() =
        runBlocking {
            val handler = RecordingHandler("Follow")

            // 署名は元のボディに対して作り、送る中身だけ入れ替える
            val request = signedRequest(body = undo(), signedBody = follow())
            val result = service(listOf(handler)).receive(recipient, request)

            assertEquals(InboxResult.Unauthorized, result)
            assertTrue(handler.calls.isEmpty(), "${handler.calls.size}")
        }

    @Test
    fun `JSON として読めないボディは弾く`() =
        runBlocking {
            val handler = RecordingHandler("Follow")

            val result = service(listOf(handler)).receive(recipient, signedRequest("not json".toByteArray()))

            assertEquals(InboxResult.BadRequest, result)
            assertTrue(handler.calls.isEmpty(), "${handler.calls.size}")
        }

    @Test
    fun `actor が署名者と違えば拒否する`() =
        runBlocking {
            val handler = RecordingHandler("Follow")

            // 署名は自分の鍵で正しく作りつつ、actor だけ他人を名乗る
            val request = signedRequest(follow(actor = "https://remote.example/users/bob"))
            val result = service(listOf(handler)).receive(recipient, request)

            assertEquals(InboxResult.Unauthorized, result)
            assertTrue(handler.calls.isEmpty(), "${handler.calls.size}")
        }

    @Test
    fun `actor が無いアクティビティも受け取る`() =
        runBlocking {
            val handler = RecordingHandler("Follow")
            val body =
                """
                {"id":"https://remote.example/activities/3","type":"Follow","object":"${recipientUrls.actorId}"}
                """.trimIndent().toByteArray()

            val result = service(listOf(handler)).receive(recipient, signedRequest(body))

            // 名乗っていない以上なりすましようが無いので、署名した相手のものとして扱う
            assertEquals(InboxResult.Accepted, result)
            val call = handler.calls.singleOrNull() ?: fail("ハンドラが呼ばれていない")
            assertNull(call.activity.actorId)
            assertEquals(TestRemoteActor.ACTOR_ID, call.verifiedSignerActorId)
        }

    @Test
    fun `鍵を引けない自分自身の Delete は受け流す`() =
        runBlocking {
            val handler = RecordingHandler("Delete")

            val result =
                service(listOf(handler), remoteActors = TestRemoteActors())
                    .receive(recipient, signedRequest(delete()))

            // 落とすと相手が送り直し続ける。検証は通っていないので掃除はしない
            assertEquals(InboxResult.Accepted, result)
            assertTrue(handler.calls.isEmpty(), "${handler.calls.size}")
        }

    @Test
    fun `消えたアクターでもフォローの記録があれば Delete を検証できる`() =
        runBlocking {
            val handler = RecordingHandler("Delete")
            val publicKeys =
                RecordedFallbackPublicKeys(
                    // アカウントが消えたと答えるサーバー
                    remote = TestRemoteActors(missing = PublicKeyLookup.Gone),
                    followers = recordedFollower(),
                    favourites = FakeFavouriteStore(),
                    stamps = FakeStampStore(),
                )

            val result =
                service(listOf(handler), publicKeys = publicKeys)
                    .receive(recipient, signedRequest(delete()))

            assertEquals(InboxResult.Accepted, result)
            val call = handler.calls.singleOrNull() ?: fail("ハンドラが呼ばれていない")
            assertEquals(TestRemoteActor.ACTOR_ID, call.verifiedSignerActorId)
        }

    private fun recordedFavourite(): FakeFavouriteStore =
        FakeFavouriteStore().apply {
            add(
                ReceivedFavourite(
                    notePublicId = PublicNoteId("note1"),
                    actor = TestRemoteActor.actor,
                    receivedAt = Instant.now(),
                ),
            )
        }

    @Test
    fun `フォロワーでなくてもお気に入りの記録があれば消えたアクターの Delete を検証できる`() =
        runBlocking {
            val handler = RecordingHandler("Delete")
            val publicKeys =
                RecordedFallbackPublicKeys(
                    remote = TestRemoteActors(missing = PublicKeyLookup.Gone),
                    followers = FakeFollowerStore(),
                    favourites = recordedFavourite(),
                    stamps = FakeStampStore(),
                )

            val result =
                service(listOf(handler), publicKeys = publicKeys)
                    .receive(recipient, signedRequest(delete()))

            assertEquals(InboxResult.Accepted, result)
            val call = handler.calls.singleOrNull() ?: fail("ハンドラが呼ばれていない")
            assertEquals(TestRemoteActor.ACTOR_ID, call.verifiedSignerActorId)
        }

    @Test
    fun `同じ type のハンドラが複数あれば作れない`() {
        // 黙って片方が捨てられると、処理されないアクティビティが相手からは無反応に見える
        val failure =
            assertFailsWith<IllegalArgumentException> {
                service(listOf(RecordingHandler("Follow"), RecordingHandler("Follow")))
            }

        assertTrue(failure.message?.contains("Follow") == true, "${failure.message}")
    }
}
