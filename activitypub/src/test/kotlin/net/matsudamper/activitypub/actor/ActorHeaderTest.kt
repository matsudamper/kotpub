package net.matsudamper.activitypub.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.matsudamper.activitypub.TestActorKey
import net.matsudamper.activitypub.TestLocalActor

class ActorHeaderTest {
    @Test
    fun `フィードにヘッダーがあればimageにこちらのURLが入る`() {
        val username = TestLocalActor.STORED_USERNAME
        val urls = assertNotNull(TestLocalActor.directory.resolve(username))
        val actor = actorDocument(
            urls = urls,
            actorKey = TestActorKey.value,
            feedLinks = TestLocalActor.feedLinks.find(username),
            profile = TestLocalActor.profiles.find(username),
            webPages = null,
        )

        assertEquals(
            "https://example.com/users/$username/header?v=${TestLocalActor.FEED_HEADER_VERSION}",
            actor.image?.url,
        )
        assertEquals("Image", actor.image?.type)
    }

    @Test
    fun `フィードを持たないアカウントはimageが入らない`() {
        val username = TestLocalActor.USERNAME
        val urls = assertNotNull(TestLocalActor.directory.resolve(username))
        val actor = actorDocument(
            urls = urls,
            actorKey = TestActorKey.value,
            feedLinks = TestLocalActor.feedLinks.find(username),
            profile = TestLocalActor.profiles.find(username),
            webPages = null,
        )

        assertNull(actor.image)
    }
}
