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
import net.matsudamper.activitypub.actor.RemoteActorProfile
import net.matsudamper.activitypub.json.AppJson

// プロフィールの編集の追従。
// Update は投稿の編集にも使われるので、object が送り主自身かどうかで見分ける。
class UpdateActorHandlerTest {
    private val now = Instant.parse("2026-08-10T00:00:00Z")

    private fun followers(): FakeFollowerStore = FakeFollowerStore().apply {
        record(
            username = TestLocalActor.USERNAME,
            follower = RemoteActor(
                actorId = TestRemoteActor.ACTOR_ID,
                inbox = TestRemoteActor.INBOX,
                sharedInbox = null,
                publicKeyPem = "pem",
                profile = RemoteActorProfile(
                    preferredUsername = "alice",
                    displayName = "アリス",
                    profileUrl = "https://remote.example/@alice",
                    iconUrl = "https://files.remote.example/old.png",
                ),
            ),
            followActivityUri = "https://remote.example/activities/1",
            receivedAt = now,
            acceptBody = "{}",
        )
        markAccepted(TestLocalActor.USERNAME, TestRemoteActor.ACTOR_ID)
    }

    private suspend fun handle(
        store: FakeFollowerStore,
        json: String,
    ) {
        val rawActivityJson = AppJson.parseToJsonElement(json) as JsonObject
        UpdateActorHandler(store).handle(
            recipient = InboxRecipient.Account(TestLocalActor.urls),
            verifiedSignerActorId = TestRemoteActor.ACTOR_ID,
            activity = AppJson.decodeFromJsonElement(InboxActivity.serializer(), rawActivityJson),
            rawActivityJson = rawActivityJson,
        )
    }

    private fun FakeFollowerStore.profile(): RemoteActorProfile =
        rows.single { it.followerActorUri == TestRemoteActor.ACTOR_ID }.profile

    @Test
    fun `自分自身の Update で表示名とアイコンが入れ替わる`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/9","type":"Update",
             "actor":"${TestRemoteActor.ACTOR_ID}",
             "object":{"id":"${TestRemoteActor.ACTOR_ID}","type":"Person",
                       "preferredUsername":"alice","name":"アリス（改名）",
                       "url":"https://remote.example/@alice",
                       "icon":{"type":"Image","url":"https://files.remote.example/new.png"}}}
            """.trimIndent(),
        )

        assertEquals("アリス（改名）", store.profile().displayName)
        assertEquals("https://files.remote.example/new.png", store.profile().iconUrl)
    }

    @Test
    fun `投稿の Update では何もしない`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/9","type":"Update",
             "actor":"${TestRemoteActor.ACTOR_ID}",
             "object":{"id":"https://remote.example/notes/1","type":"Note","name":"投稿の名前"}}
            """.trimIndent(),
        )

        assertEquals("アリス", store.profile().displayName)
    }

    @Test
    fun `中身が埋め込まれていない Update では何もしない`() = runBlocking {
        val store = followers()

        handle(
            store,
            """
            {"id":"https://remote.example/activities/9","type":"Update",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"${TestRemoteActor.ACTOR_ID}"}
            """.trimIndent(),
        )

        assertEquals("アリス", store.profile().displayName)
        assertEquals("https://files.remote.example/old.png", store.profile().iconUrl)
    }
}
