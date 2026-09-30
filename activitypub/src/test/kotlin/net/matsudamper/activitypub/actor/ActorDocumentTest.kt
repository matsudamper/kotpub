package net.matsudamper.activitypub.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.matsudamper.activitypub.TestActorKey
import net.matsudamper.activitypub.TestLocalActor
import net.matsudamper.activitypub.activitypub.Actor

class ActorDocumentTest {
    @Test
    fun `ヘッダーの版があればimageにこちらのURLが入る`() {
        val actor = actorDocumentOf(TestLocalActor.STORED_USERNAME)

        assertEquals(
            "https://example.com/users/${TestLocalActor.STORED_USERNAME}/header?v=${TestLocalActor.HEADER_VERSION}",
            actor.image?.url,
        )
        assertEquals("Image", actor.image?.type)
    }

    @Test
    fun `アイコンの版があればiconにこちらのURLが入る`() {
        val actor = actorDocumentOf(TestLocalActor.STORED_USERNAME)

        assertEquals(
            "https://example.com/users/${TestLocalActor.STORED_USERNAME}/icon?v=${TestLocalActor.ICON_VERSION}",
            actor.icon?.url,
        )
    }

    @Test
    fun `画像を持たないアカウントはiconとimageが入らない`() {
        val actor = actorDocumentOf(TestLocalActor.USERNAME)

        assertNull(actor.icon)
        assertNull(actor.image)
    }

    @Test
    fun `リンクは渡した見出しで並び httpとhttps以外は出さない`() {
        val actor = actorDocumentOf(TestLocalActor.STORED_USERNAME)

        assertEquals(listOf("サイト"), actor.attachment.map { it.name })
    }

    @Test
    fun `説明文が無ければsummaryを出さない`() {
        val actor = actorDocumentOf(TestLocalActor.USERNAME)

        assertNull(actor.summary)
    }

    @Test
    fun `説明文はHTMLにして出す`() {
        val actor = actorDocumentOf(TestLocalActor.STORED_USERNAME)

        assertEquals("<p>1 つ目のフィード<br>&lt;b&gt;タグ&lt;/b&gt;</p>", actor.summary)
    }

    private fun actorDocumentOf(username: String): Actor {
        val urls = assertNotNull(TestLocalActor.directory.resolve(username))
        return actorDocument(
            urls = urls,
            actorKey = TestActorKey.value,
            appearance = TestLocalActor.appearances.find(username),
            profile = TestLocalActor.profiles.find(username),
            webPages = null,
        )
    }
}
