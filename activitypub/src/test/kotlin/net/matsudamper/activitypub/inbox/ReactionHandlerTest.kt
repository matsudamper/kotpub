package net.matsudamper.activitypub.inbox

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.FakeEarlyUndoneLikes
import net.matsudamper.activitypub.FakeFavouriteStore
import net.matsudamper.activitypub.FakeStampStore
import net.matsudamper.activitypub.TestLocalActor
import net.matsudamper.activitypub.TestRemoteActor
import net.matsudamper.activitypub.TestRemoteActors
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.json.AppJson

class ReactionHandlerTest {
    private val notePublicId = PublicNoteId("note1")

    private val noteUrl = "https://${TestLocalActor.DOMAIN}/notes/${notePublicId.value}"

    private val knownActor = TestRemoteActors(actors = mapOf(TestRemoteActor.ACTOR_ID to TestRemoteActor.actor))

    private class Recorded(
        val favourites: FakeFavouriteStore,
        val stamps: FakeStampStore,
    )

    private suspend fun handle(
        type: String,
        json: String,
        remoteActors: TestRemoteActors,
        earlyUndoneLikes: FakeEarlyUndoneLikes,
    ): Recorded {
        val recorded = Recorded(favourites = FakeFavouriteStore(), stamps = FakeStampStore())
        val rawActivityJson = AppJson.parseToJsonElement(json) as JsonObject
        ReactionHandler(
            type = type,
            domain = TestLocalActor.DOMAIN,
            remoteActors = remoteActors,
            favourites = recorded.favourites,
            stamps = recorded.stamps,
            earlyUndoneLikes = earlyUndoneLikes,
        ).handle(
            recipient = InboxRecipient.Account(TestLocalActor.urls),
            verifiedSignerActorId = TestRemoteActor.ACTOR_ID,
            activity = AppJson.decodeFromJsonElement(InboxActivity.serializer(), rawActivityJson),
            rawActivityJson = rawActivityJson,
        )
        return recorded
    }

    private suspend fun handleLike(
        json: String,
        remoteActors: TestRemoteActors,
        earlyUndoneLikes: FakeEarlyUndoneLikes,
    ): Recorded = handle(type = ReactionHandler.LIKE_TYPE, json = json, remoteActors = remoteActors, earlyUndoneLikes = earlyUndoneLikes)

    private suspend fun handleLike(json: String): Recorded =
        handleLike(json = json, remoteActors = knownActor, earlyUndoneLikes = FakeEarlyUndoneLikes())

    @Test
    fun `content の無い Like をお気に入りとして記録する`() = runBlocking {
        val recorded = handleLike(
            """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl"}
            """.trimIndent(),
        )

        val favourite = recorded.favourites.rows.single()
        assertEquals(notePublicId, favourite.notePublicId)
        assertEquals(TestRemoteActor.ACTOR_ID, favourite.actor.actorId)
        assertTrue(recorded.stamps.rows.isEmpty())
    }

    @Test
    fun `絵文字付きの Like はお気に入りではなくスタンプとして記録する`() = runBlocking {
        val recorded = handleLike(
            """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl","content":"👍"}
            """.trimIndent(),
        )

        val stamp = recorded.stamps.rows.single()
        assertEquals(notePublicId, stamp.notePublicId)
        assertEquals("👍", stamp.emoji)
        assertNull(stamp.emojiImageUrl)
        assertTrue(recorded.favourites.rows.isEmpty())
    }

    @Test
    fun `カスタム絵文字の画像を tag から引く`() = runBlocking {
        val recorded = handleLike(
            """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl","content":":kawaii:",
             "tag":[{"type":"Emoji","name":":kawaii:","icon":{"type":"Image","url":"https://remote.example/kawaii.png"}}]}
            """.trimIndent(),
        )

        val stamp = recorded.stamps.rows.single()
        assertEquals(":kawaii:", stamp.emoji)
        assertEquals("https://remote.example/kawaii.png", stamp.emojiImageUrl)
    }

    @Test
    fun `http と https 以外の画像 URL は記録しない`() = runBlocking {
        val recorded = handleLike(
            """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl","content":":kawaii:",
             "tag":{"type":"Emoji","name":":kawaii:","icon":{"type":"Image","url":"javascript:alert(1)"}}}
            """.trimIndent(),
        )

        assertNull(recorded.stamps.rows.single().emojiImageUrl)
    }

    @Test
    fun `EmojiReact をスタンプとして記録する`() = runBlocking {
        val recorded = handle(
            type = ReactionHandler.EMOJI_REACT_TYPE,
            json = """
            {"id":"https://remote.example/reacts/1","type":"EmojiReact",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl","content":"🎉"}
            """.trimIndent(),
            remoteActors = knownActor,
            earlyUndoneLikes = FakeEarlyUndoneLikes(),
        )

        assertEquals("🎉", recorded.stamps.rows.single().emoji)
        assertTrue(recorded.favourites.rows.isEmpty())
    }

    @Test
    fun `絵文字の無い EmojiReact は何も記録しない`() = runBlocking {
        val recorded = handle(
            type = ReactionHandler.EMOJI_REACT_TYPE,
            json = """
            {"id":"https://remote.example/reacts/1","type":"EmojiReact",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl"}
            """.trimIndent(),
            remoteActors = knownActor,
            earlyUndoneLikes = FakeEarlyUndoneLikes(),
        )

        assertTrue(recorded.stamps.rows.isEmpty())
        assertTrue(recorded.favourites.rows.isEmpty())
    }

    @Test
    fun `絵文字 1 つとして扱えない長さの content は記録しない`() = runBlocking {
        val recorded = handleLike(
            """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl","content":"${"あ".repeat(101)}"}
            """.trimIndent(),
        )

        assertTrue(recorded.stamps.rows.isEmpty())
        assertTrue(recorded.favourites.rows.isEmpty())
    }

    @Test
    fun `id の無い Like も記録する`() = runBlocking {
        val recorded = handleLike(
            """
            {"type":"Like","actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl"}
            """.trimIndent(),
        )

        assertEquals(1, recorded.favourites.rows.size)
    }

    @Test
    fun `よそのサーバーの投稿へのお気に入りは記録しない`() = runBlocking {
        val recorded = handleLike(
            """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"https://other.example/notes/1"}
            """.trimIndent(),
        )

        assertTrue(recorded.favourites.rows.isEmpty())
    }

    @Test
    fun `押した相手のアクター文書を引けなければ記録しない`() = runBlocking {
        val recorded = handleLike(
            json = """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl"}
            """.trimIndent(),
            remoteActors = TestRemoteActors(),
            earlyUndoneLikes = FakeEarlyUndoneLikes(),
        )

        assertTrue(recorded.favourites.rows.isEmpty())
    }

    @Test
    fun `先に取り消しが届いた Like は記録しない`() = runBlocking {
        val earlyUndoneLikes = FakeEarlyUndoneLikes().apply {
            remember(actorUri = TestRemoteActor.ACTOR_ID, activityUri = "https://remote.example/likes/1", expiresAt = Instant.MAX)
        }

        val recorded = handleLike(
            json = """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl"}
            """.trimIndent(),
            remoteActors = knownActor,
            earlyUndoneLikes = earlyUndoneLikes,
        )

        assertTrue(recorded.favourites.rows.isEmpty())
    }

    @Test
    fun `先に取り消しが届いた絵文字付きの Like はスタンプとしても記録しない`() = runBlocking {
        val earlyUndoneLikes = FakeEarlyUndoneLikes().apply {
            remember(actorUri = TestRemoteActor.ACTOR_ID, activityUri = "https://remote.example/likes/1", expiresAt = Instant.MAX)
        }

        val recorded = handleLike(
            json = """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl","content":"👍"}
            """.trimIndent(),
            remoteActors = knownActor,
            earlyUndoneLikes = earlyUndoneLikes,
        )

        assertTrue(recorded.stamps.rows.isEmpty())
    }

    @Test
    fun `期限を過ぎた取り消しでは Like を止めない`() = runBlocking {
        val earlyUndoneLikes = FakeEarlyUndoneLikes().apply {
            remember(
                actorUri = TestRemoteActor.ACTOR_ID,
                activityUri = "https://remote.example/likes/1",
                expiresAt = Instant.now().minusSeconds(1),
            )
        }

        val recorded = handleLike(
            json = """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl"}
            """.trimIndent(),
            remoteActors = knownActor,
            earlyUndoneLikes = earlyUndoneLikes,
        )

        assertEquals(1, recorded.favourites.rows.size)
    }

    @Test
    fun `別の相手が先に取り消しを送っていても記録する`() = runBlocking {
        val earlyUndoneLikes = FakeEarlyUndoneLikes().apply {
            remember(actorUri = "https://remote.example/users/bob", activityUri = "https://remote.example/likes/1", expiresAt = Instant.MAX)
        }

        val recorded = handleLike(
            json = """
            {"id":"https://remote.example/likes/1","type":"Like",
             "actor":"${TestRemoteActor.ACTOR_ID}","object":"$noteUrl"}
            """.trimIndent(),
            remoteActors = knownActor,
            earlyUndoneLikes = earlyUndoneLikes,
        )

        assertEquals(1, recorded.favourites.rows.size)
    }
}
