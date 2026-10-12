package net.matsudamper.activitypub.inbox

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import net.matsudamper.activitypub.FakeFavouriteStore
import net.matsudamper.activitypub.FakeFollowerStore
import net.matsudamper.activitypub.FakeStampStore
import net.matsudamper.activitypub.TestRemoteActor
import net.matsudamper.activitypub.TestRemoteActors
import net.matsudamper.activitypub.actor.RemoteActor
import net.matsudamper.activitypub.crypto.RsaKeys
import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.favourite.FavouriteStore.ReceivedFavourite
import net.matsudamper.activitypub.httpsignature.PublicKeyLookup
import net.matsudamper.activitypub.stamp.StampStore.ReceivedStamp

// 消えたアクターの鍵を、フォローとお気に入りとスタンプのどの記録からも引けること。
// 相手のサーバーに GET しに行く方は TestRemoteActors で差し替える。
class RecordedFallbackPublicKeysTest {
    private val now: Instant = Instant.parse("2026-08-10T00:00:00Z")

    private fun followers(publicKeyPem: String = RsaKeys.encodeToPem(TestRemoteActor.keyPair.public)): FakeFollowerStore =
        FakeFollowerStore().apply {
            record(
                username = "admin",
                follower = RemoteActor(
                    actorId = TestRemoteActor.ACTOR_ID,
                    inbox = TestRemoteActor.INBOX,
                    sharedInbox = null,
                    publicKeyPem = publicKeyPem,
                    profile = TestRemoteActor.noProfile,
                ),
                followActivityUri = "https://remote.example/activities/1",
                receivedAt = now,
                acceptBody = "{}",
            )
        }

    /**
     * アクターが消えたと答えるサーバー
     */
    private fun gone(): TestRemoteActors = TestRemoteActors(missing = PublicKeyLookup.Gone)

    @Test
    fun `相手のサーバーから引けたらそちらを使う`() =
        runBlocking {
            val remote = TestRemoteActor.remoteActors()

            val lookup =
                RecordedFallbackPublicKeys(remote = remote, followers = followers(), favourites = FakeFavouriteStore(), stamps = FakeStampStore())
                    .find(TestRemoteActor.KEY_ID)

            assertEquals(1, remote.findCallCount)
            assertEquals(TestRemoteActor.keyPair.public, (lookup as PublicKeyLookup.Found).key.publicKey)
            assertEquals(TestRemoteActor.ACTOR_ID, lookup.key.owner)
        }

    @Test
    fun `引けたら記録の鍵を読み直したものにする`() =
        runBlocking {
            // 相手が鍵を替えてから消えると、Follow のときの鍵では Delete を検証できない
            val followers = followers(publicKeyPem = "替える前の鍵")

            RecordedFallbackPublicKeys(remote = TestRemoteActor.remoteActors(), followers = followers, favourites = FakeFavouriteStore(), stamps = FakeStampStore())
                .find(TestRemoteActor.KEY_ID)

            assertEquals(
                RsaKeys.encodeToPem(TestRemoteActor.keyPair.public),
                followers.findPublicKeyPem(TestRemoteActor.ACTOR_ID),
            )
        }

    @Test
    fun `引き直したときも記録の鍵を新しくする`() =
        runBlocking {
            // 引き直しは相手が鍵を替えたときに通る経路。記録も一緒に新しくしないと、
            // その相手が消えた後の Delete を検証できない
            val followers = followers(publicKeyPem = "替える前の鍵")

            RecordedFallbackPublicKeys(remote = TestRemoteActor.remoteActors(), followers = followers, favourites = FakeFavouriteStore(), stamps = FakeStampStore())
                .refresh(TestRemoteActor.KEY_ID)

            assertEquals(
                RsaKeys.encodeToPem(TestRemoteActor.keyPair.public),
                followers.findPublicKeyPem(TestRemoteActor.ACTOR_ID),
            )
        }

    @Test
    fun `消えた相手はフォローの記録から引く`() =
        runBlocking {
            val lookup =
                RecordedFallbackPublicKeys(remote = gone(), followers = followers(), favourites = FakeFavouriteStore(), stamps = FakeStampStore())
                    .find(TestRemoteActor.KEY_ID)

            // 削除された相手の Delete を検証できるのはこの経路だけ
            val found = lookup as PublicKeyLookup.Found
            assertEquals(TestRemoteActor.keyPair.public, found.key.publicKey)
            // 持ち主は keyId の名乗りではなく、記録したアクター id
            assertEquals(TestRemoteActor.ACTOR_ID, found.key.owner)
            assertEquals(TestRemoteActor.KEY_ID, found.key.keyId)
        }

    @Test
    fun `取りに行けなかっただけなら記録の鍵は使わない`() =
        runBlocking {
            // 相手が鍵を替えた後にこの経路を通すと、失効した鍵で署名が通ってしまう
            val lookup =
                RecordedFallbackPublicKeys(remote = TestRemoteActors(), followers = followers(), favourites = FakeFavouriteStore(), stamps = FakeStampStore())
                    .find(TestRemoteActor.KEY_ID)

            assertEquals(PublicKeyLookup.Unavailable, lookup)
        }

    private fun favourites(): FakeFavouriteStore = FakeFavouriteStore().apply {
        add(
            ReceivedFavourite(
                notePublicId = PublicNoteId("note1"),
                actor = TestRemoteActor.actor,
                receivedAt = now,
            ),
        )
    }

    @Test
    fun `消えた相手はフォローしていなくてもお気に入りの記録から引く`() =
        runBlocking {
            val lookup =
                RecordedFallbackPublicKeys(
                    remote = gone(),
                    followers = FakeFollowerStore(),
                    favourites = favourites(),
                    stamps = FakeStampStore(),
                ).find(TestRemoteActor.KEY_ID)

            val found = lookup as PublicKeyLookup.Found
            assertEquals(TestRemoteActor.keyPair.public, found.key.publicKey)
            assertEquals(TestRemoteActor.ACTOR_ID, found.key.owner)
        }

    @Test
    fun `消えた相手はスタンプしか押していなくてもスタンプの記録から引く`() =
        runBlocking {
            val stamps = FakeStampStore().apply {
                put(
                    ReceivedStamp(
                        notePublicId = PublicNoteId("note1"),
                        actor = TestRemoteActor.actor,
                        emoji = "👍",
                        emojiImageUrl = null,
                        receivedAt = now,
                    ),
                )
            }

            val lookup =
                RecordedFallbackPublicKeys(
                    remote = gone(),
                    followers = FakeFollowerStore(),
                    favourites = FakeFavouriteStore(),
                    stamps = stamps,
                ).find(TestRemoteActor.KEY_ID)

            val found = lookup as PublicKeyLookup.Found
            assertEquals(TestRemoteActor.keyPair.public, found.key.publicKey)
            assertEquals(TestRemoteActor.ACTOR_ID, found.key.owner)
        }

    @Test
    fun `フォローもお気に入りもスタンプも記録が無ければ引けない`() =
        runBlocking {
            val lookup =
                RecordedFallbackPublicKeys(remote = gone(), followers = FakeFollowerStore(), favourites = FakeFavouriteStore(), stamps = FakeStampStore())
                    .find(TestRemoteActor.KEY_ID)

            assertEquals(PublicKeyLookup.Gone, lookup)
        }

    @Test
    fun `記録した PEM を読めなければ引けない`() =
        runBlocking {
            val lookup =
                RecordedFallbackPublicKeys(
                    remote = gone(),
                    followers = followers(publicKeyPem = "鍵ではない"),
                    favourites = FakeFavouriteStore(),
                    stamps = FakeStampStore(),
                ).find(TestRemoteActor.KEY_ID)

            assertEquals(PublicKeyLookup.Gone, lookup)
        }
}
