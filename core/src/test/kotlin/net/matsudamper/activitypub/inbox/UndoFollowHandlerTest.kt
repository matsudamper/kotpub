package net.matsudamper.activitypub.inbox

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.FakeFollowerStore
import net.matsudamper.activitypub.TestLocalActor
import net.matsudamper.activitypub.TestRemoteActor
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.actor.RemoteActor
import net.matsudamper.activitypub.json.AppJson

// Undo でフォローを解除する。
// object に Follow が丸ごと入っている形と、id だけの形の両方が届く。
class UndoFollowHandlerTest {
    private val recipient = TestLocalActor.urls

    private val followUri = "https://remote.example/activities/1"

    private val now = Instant.parse("2026-08-10T00:00:00Z")

    private fun followers(): FakeFollowerStore = FakeFollowerStore().apply {
        record(
            username = TestLocalActor.USERNAME,
            follower = RemoteActor(
                actorId = TestRemoteActor.ACTOR_ID,
                inbox = TestRemoteActor.INBOX,
                sharedInbox = null,
                publicKeyPem = "pem",
                profile = TestRemoteActor.noProfile,
            ),
            followActivityUri = followUri,
            receivedAt = now,
            acceptBody = "{}",
        )
        markAccepted(TestLocalActor.USERNAME, TestRemoteActor.ACTOR_ID)
    }

    private suspend fun handle(
        store: FakeFollowerStore,
        json: String,
        verifiedSignerActorId: String = TestRemoteActor.ACTOR_ID,
        inbox: InboxRecipient = InboxRecipient.Account(recipient),
    ) {
        val rawActivityJson = AppJson.parseToJsonElement(json) as JsonObject
        UndoFollowHandler(directory = TestLocalActor.directory, followers = store).handle(
            recipient = inbox,
            verifiedSignerActorId = verifiedSignerActorId,
            activity = AppJson.decodeFromJsonElement(InboxActivity.serializer(), rawActivityJson),
        )
    }

    @Test
    fun `object に Follow が埋まっていれば解除する`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/2","type":"Undo",
             "actor":"${TestRemoteActor.ACTOR_ID}",
             "object":{"id":"$followUri","type":"Follow",
                       "actor":"${TestRemoteActor.ACTOR_ID}","object":"${recipient.actorId}"}}
            """.trimIndent(),
        )

        assertEquals(0, store.count(TestLocalActor.USERNAME))
    }

    @Test
    fun `object が Follow の id だけでも解除する`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/2","type":"Undo",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$followUri"}
            """.trimIndent(),
        )

        assertEquals(0, store.count(TestLocalActor.USERNAME))
    }

    @Test
    fun `記録していない id の Undo では解除しない`() = runBlocking {
        val store = followers()

        // Undo{Like} などが id だけで来た場合。何のアクティビティだったのかは
        // こちらの記録と突き合わせるしか判断材料が無い
        handle(
            store,
            """
            {"id":"https://remote.example/activities/2","type":"Undo",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"https://remote.example/likes/9"}
            """.trimIndent(),
        )

        assertEquals(1, store.count(TestLocalActor.USERNAME))
    }

    @Test
    fun `対象が Follow でなければ解除しない`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/2","type":"Undo",
             "actor":"${TestRemoteActor.ACTOR_ID}",
             "object":{"id":"https://remote.example/likes/9","type":"Like",
                       "actor":"${TestRemoteActor.ACTOR_ID}","object":"${recipient.actorId}"}}
            """.trimIndent(),
        )

        assertEquals(1, store.count(TestLocalActor.USERNAME))
    }

    @Test
    fun `他人のフォローは解除できない`() = runBlocking {
        val store = followers()

        // 署名の持ち主とは別の相手のフォローを消そうとする形。
        // object の中身ではなく署名者で絞るので消えない
        handle(
            store,
            """
            {"id":"https://remote.example/activities/2","type":"Undo",
             "actor":"https://remote.example/users/mallory",
             "object":{"id":"$followUri","type":"Follow",
                       "actor":"${TestRemoteActor.ACTOR_ID}","object":"${recipient.actorId}"}}
            """.trimIndent(),
            verifiedSignerActorId = "https://remote.example/users/mallory",
        )

        assertEquals(1, store.count(TestLocalActor.USERNAME))
    }

    @Test
    fun `Follow の object の綴りが大文字でも解除する`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/2","type":"Undo",
             "actor":"${TestRemoteActor.ACTOR_ID}",
             "object":{"id":"$followUri","type":"Follow",
                       "actor":"${TestRemoteActor.ACTOR_ID}","object":"https://${TestLocalActor.DOMAIN}/users/ADMIN"}}
            """.trimIndent(),
        )

        assertEquals(0, store.count(TestLocalActor.USERNAME))
    }

    @Test
    fun `共有 inbox では埋まっている Follow の object から宛先のアカウントを引いて解除する`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/2","type":"Undo",
             "actor":"${TestRemoteActor.ACTOR_ID}",
             "object":{"id":"$followUri","type":"Follow",
                       "actor":"${TestRemoteActor.ACTOR_ID}","object":"${recipient.actorId}"}}
            """.trimIndent(),
            inbox = InboxRecipient.Shared,
        )

        assertEquals(0, store.count(TestLocalActor.USERNAME))
    }

    @Test
    fun `共有 inbox に届いた id だけの Undo は、記録している Follow の id から宛先を引いて解除する`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/2","type":"Undo",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$followUri"}
            """.trimIndent(),
            inbox = InboxRecipient.Shared,
        )

        assertEquals(0, store.count(TestLocalActor.USERNAME))
    }

    @Test
    fun `共有 inbox に届いた id だけの Undo が記録に無い Follow を指していれば何もしない`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/2","type":"Undo",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"https://remote.example/activities/unknown"}
            """.trimIndent(),
            inbox = InboxRecipient.Shared,
        )

        assertEquals(1, store.count(TestLocalActor.USERNAME))
    }
}
