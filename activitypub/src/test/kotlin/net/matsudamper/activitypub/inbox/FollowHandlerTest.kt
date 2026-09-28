package net.matsudamper.activitypub.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.FakeFollowerStore
import net.matsudamper.activitypub.TestLocalActor
import net.matsudamper.activitypub.TestRemoteActor
import net.matsudamper.activitypub.TestRemoteActors
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.activity.OutgoingActivity
import net.matsudamper.activitypub.actor.RemoteActors
import net.matsudamper.activitypub.json.AppJson

// Follow を受けてフォロワーとして記録し、Accept を預けるところまで。
// 記録と Accept が一方だけ残らないことがこのハンドラの肝になる。
class FollowHandlerTest {
    private val recipient = TestLocalActor.urls

    private fun followJson(
        id: String? = "https://remote.example/activities/1",
        target: String = recipient.actorId,
    ): String {
        val idField = if (id == null) "" else """"id":"$id","""
        return """{$idField"type":"Follow","actor":"${TestRemoteActor.ACTOR_ID}","object":"$target"}"""
    }

    private fun followHandler(
        followers: FakeFollowerStore,
        remoteActors: RemoteActors = TestRemoteActor.remoteActors(),
    ): FollowHandler = FollowHandler(
        directory = TestLocalActor.directory,
        remoteActors = remoteActors,
        followers = followers,
    )

    private suspend fun handle(
        handler: FollowHandler,
        json: String,
        inbox: InboxRecipient = InboxRecipient.Account(recipient),
    ) {
        val rawActivityJson = AppJson.parseToJsonElement(json) as JsonObject
        handler.handle(
            recipient = inbox,
            verifiedSignerActorId = TestRemoteActor.ACTOR_ID,
            activity = AppJson.decodeFromJsonElement(InboxActivity.serializer(), rawActivityJson),
            rawActivityJson = rawActivityJson,
        )
    }

    private fun acceptOf(followers: FakeFollowerStore): OutgoingActivity =
        AppJson.decodeFromString(OutgoingActivity.serializer(), followers.rows.single().acceptBody)

    @Test
    fun `記録と一緒に Accept を預ける`() = runBlocking {
        val followers = FakeFollowerStore()

        handle(followHandler(followers), followJson())

        val row = followers.rows.single()
        assertEquals(TestLocalActor.USERNAME, row.username)
        assertEquals(TestRemoteActor.ACTOR_ID, row.followerActorUri)
        // 記録する inbox と鍵は、Accept を送るのに引いたアクター文書から取る
        assertEquals(TestRemoteActor.INBOX, row.inbox)
        assertTrue(row.publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----"))
        assertEquals("https://remote.example/activities/1", row.followActivityUri)

        val accept = acceptOf(followers)
        assertEquals(OutgoingActivity.TYPE_ACCEPT, accept.type)
        assertEquals(recipient.actorId, accept.actor)
    }

    @Test
    fun `預けただけではフォロワーに数えない`() = runBlocking {
        val followers = FakeFollowerStore()

        handle(followHandler(followers), followJson())

        // 数え始めるのは Accept が届いてから。届く前に配ると、相手からは
        // フォローしていないアカウントの投稿が流れてくることになる
        assertFalse(followers.rows.single().accepted)
        assertEquals(0, followers.count(TestLocalActor.USERNAME))
    }

    @Test
    fun `送り直された Follow への Accept は毎回違う id になる`() = runBlocking {
        val followers = FakeFollowerStore()
        val handler = followHandler(followers)

        handle(handler, followJson())
        val first = acceptOf(followers).id
        handle(handler, followJson())

        // 相手は id で重複を判断する。同じ id だと送り直した Accept が落ちる
        assertEquals(1, followers.rows.size)
        assertTrue(first != acceptOf(followers).id)
    }

    @Test
    fun `記録できなければ Accept も預けない`() = runBlocking {
        val followers = FakeFollowerStore(failOnRecord = true)

        handle(followHandler(followers), followJson())

        // 送り先が残らないのに相手だけがフォローできたつもりになる状態を作らない
        assertEquals(emptyList(), followers.rows)
    }

    @Test
    fun `id の無い Follow は受け付けない`() = runBlocking {
        val followers = FakeFollowerStore()

        handle(followHandler(followers), followJson(id = null))

        // 送り直しと新しい Follow を区別できないので記録も Accept もしない
        assertEquals(emptyList(), followers.rows)
    }

    @Test
    fun `宛先が違う Follow は記録しない`() = runBlocking {
        val followers = FakeFollowerStore()

        handle(followHandler(followers), followJson(target = "https://example.com/users/someone-else"))

        assertEquals(emptyList(), followers.rows)
    }

    @Test
    fun `相手のアクターを引けなければ記録しない`() = runBlocking {
        val followers = FakeFollowerStore()

        handle(followHandler(followers = followers, remoteActors = TestRemoteActors()), followJson())

        // inbox も鍵も取れていないので、記録する中身が揃っていない
        assertEquals(emptyList(), followers.rows)
    }

    @Test
    fun `同じ Follow を二重に受けても行が増えない`() = runBlocking {
        val followers = FakeFollowerStore()
        val handler = followHandler(followers)

        handle(handler, followJson())
        handle(handler, followJson())

        assertEquals(1, followers.rows.size)
    }

    @Test
    fun `共有 inbox では object のアクターを宛先として記録する`() = runBlocking {
        val followers = FakeFollowerStore()
        val followee = TestLocalActor.directory.resolve(TestLocalActor.STORED_USERNAME) ?: error("テスト用のアカウントが無い")

        handle(followHandler(followers), followJson(target = followee.actorId), inbox = InboxRecipient.Shared)

        assertEquals(TestLocalActor.STORED_USERNAME, followers.rows.single().username)
        assertEquals(followee.actorId, acceptOf(followers).actor)
    }

    @Test
    fun `共有 inbox でも object がこちらのアカウントでなければ記録しない`() = runBlocking {
        val followers = FakeFollowerStore()

        handle(
            followHandler(followers),
            followJson(target = "https://${TestLocalActor.DOMAIN}/users/nobody"),
            inbox = InboxRecipient.Shared,
        )

        assertTrue(followers.rows.isEmpty())
    }
}
