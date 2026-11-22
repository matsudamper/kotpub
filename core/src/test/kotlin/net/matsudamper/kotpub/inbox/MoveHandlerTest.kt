package net.matsudamper.kotpub.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.TestLocalActor
import net.matsudamper.kotpub.TestRemoteActor
import net.matsudamper.kotpub.TestRemoteActors
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.actor.RemoteActor
import net.matsudamper.kotpub.actor.RemoteActorLookup
import net.matsudamper.kotpub.actor.RemoteActors
import net.matsudamper.kotpub.follower.MovedActorFollowings
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.json.AppJson

// 引っ越したアカウントのフォローを移り先へ付け替える。
// 移り先が移り元を alsoKnownAs に入れていなければ、他人のアカウントを名乗った Move として扱う。
class MoveHandlerTest {
    private class RecordingMovedActorFollowings : MovedActorFollowings {
        val moves = mutableListOf<Pair<String, String>>()

        override suspend fun move(
            movedFromActorId: String,
            movedTo: RemoteActor,
        ) {
            moves += movedFromActorId to movedTo.actorId
        }
    }

    private fun movedTo(alsoKnownAs: List<String>): RemoteActor =
        RemoteActor(
            actorId = MOVED_TO,
            inbox = "$MOVED_TO/inbox",
            sharedInbox = null,
            publicKeyPem = "pem",
            profile = TestRemoteActor.noProfile,
            alsoKnownAs = alsoKnownAs,
        )

    private suspend fun handle(
        followings: RecordingMovedActorFollowings,
        remoteActors: RemoteActors,
        json: String,
    ): InboxHandling {
        val rawActivityJson = AppJson.parseToJsonElement(json) as JsonObject
        return MoveHandler(remoteActors = remoteActors, followings = followings).handle(
            recipient = InboxRecipient.Account(TestLocalActor.urls),
            verifiedSignerActorId = TestRemoteActor.ACTOR_ID,
            activity = AppJson.decodeFromJsonElement(InboxActivity.serializer(), rawActivityJson),
            rawActivityJson = rawActivityJson,
        )
    }

    private fun move(
        movedFrom: String,
        target: String,
    ): String =
        """
        {"id":"https://remote.example/activities/move","type":"Move",
         "actor":"${TestRemoteActor.ACTOR_ID}","object":"$movedFrom","target":"$target"}
        """.trimIndent()

    @Test
    fun `移り先が移り元を alsoKnownAs に入れていれば付け替える`() {
        runBlocking {
            val followings = RecordingMovedActorFollowings()

            val handling = handle(
                followings,
                TestRemoteActors(actors = mapOf(MOVED_TO to movedTo(alsoKnownAs = listOf(TestRemoteActor.ACTOR_ID)))),
                move(movedFrom = TestRemoteActor.ACTOR_ID, target = MOVED_TO),
            )

            assertEquals(InboxHandling.Handled, handling)
            assertEquals(listOf(TestRemoteActor.ACTOR_ID to MOVED_TO), followings.moves)
        }
    }

    @Test
    fun `移り先が移り元を alsoKnownAs に入れていなければ何もしない`() {
        runBlocking {
            val followings = RecordingMovedActorFollowings()

            val handling = handle(
                followings,
                TestRemoteActors(actors = mapOf(MOVED_TO to movedTo(alsoKnownAs = listOf()))),
                move(movedFrom = TestRemoteActor.ACTOR_ID, target = MOVED_TO),
            )

            assertIs<InboxHandling.Ignored>(handling)
            assertEquals(listOf(), followings.moves)
        }
    }

    @Test
    fun `移り元が送り主自身でなければ何もしない`() {
        runBlocking {
            val followings = RecordingMovedActorFollowings()
            val otherActor = "https://remote.example/users/bob"

            val handling = handle(
                followings,
                TestRemoteActors(actors = mapOf(MOVED_TO to movedTo(alsoKnownAs = listOf(otherActor)))),
                move(movedFrom = otherActor, target = MOVED_TO),
            )

            assertIs<InboxHandling.Ignored>(handling)
            assertEquals(listOf(), followings.moves)
        }
    }

    @Test
    fun `移り先の文書が別のアクターを名乗っていれば何もしない`() {
        runBlocking {
            val followings = RecordingMovedActorFollowings()
            val forgedDocumentUrl = "https://new.example/uploads/actor.json"

            val handling = handle(
                followings,
                TestRemoteActors(actors = mapOf(forgedDocumentUrl to movedTo(alsoKnownAs = listOf(TestRemoteActor.ACTOR_ID)))),
                move(movedFrom = TestRemoteActor.ACTOR_ID, target = forgedDocumentUrl),
            )

            assertIs<InboxHandling.Ignored>(handling)
            assertEquals(listOf(), followings.moves)
        }
    }

    @Test
    fun `移り先を一時的に取得できなければ送り直してもらう`() {
        runBlocking {
            val followings = RecordingMovedActorFollowings()

            // TestRemoteActors は取得できない相手に 503 を返す
            assertFailsWith<IllegalStateException> {
                handle(
                    followings,
                    TestRemoteActors(),
                    move(movedFrom = TestRemoteActor.ACTOR_ID, target = MOVED_TO),
                )
            }
            assertEquals(listOf(), followings.moves)
        }
    }

    @Test
    fun `移り先の本文を途中で読めなければ送り直してもらう`() {
        runBlocking {
            val followings = RecordingMovedActorFollowings()
            val bodyUnreadableActors = object : RemoteActors by TestRemoteActors() {
                override suspend fun findActor(actorId: String): RemoteActorLookup =
                    RemoteActorLookup.Unavailable(RemoteFetchFailure.BodyUnreadable(actorId, IllegalStateException("切断")))
            }

            assertFailsWith<IllegalStateException> {
                handle(
                    followings,
                    bodyUnreadableActors,
                    move(movedFrom = TestRemoteActor.ACTOR_ID, target = MOVED_TO),
                )
            }
            assertEquals(listOf(), followings.moves)
        }
    }

    @Test
    fun `移り先がもう無ければ何もしない`() {
        runBlocking {
            val followings = RecordingMovedActorFollowings()

            val goneActors = object : RemoteActors by TestRemoteActors() {
                override suspend fun findActor(actorId: String): RemoteActorLookup = RemoteActorLookup.Gone
            }

            val handling = handle(
                followings,
                goneActors,
                move(movedFrom = TestRemoteActor.ACTOR_ID, target = MOVED_TO),
            )

            assertIs<InboxHandling.Ignored>(handling)
            assertEquals(listOf(), followings.moves)
        }
    }

    private companion object {
        const val MOVED_TO = "https://new.example/users/alice"
    }
}
