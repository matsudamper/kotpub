package net.matsudamper.kotpub.note

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.matsudamper.kotpub.FakeFollowerStore
import net.matsudamper.kotpub.FakeNoteStore
import net.matsudamper.kotpub.TestDelivery
import net.matsudamper.kotpub.TestLocalActor
import net.matsudamper.kotpub.TestWebPageUrls
import net.matsudamper.kotpub.activity.ActivityStreamsIri
import net.matsudamper.kotpub.activity.CreateNoteActivity
import net.matsudamper.kotpub.json.AppJson

// フォロワーに送る Create{Note} を組み立てるところ。
// 記録も配信もここではしない。組み立てた JSON が Mastodon に通る形かどうかが肝。
class NotePublisherTest {
    private val sender = TestLocalActor.urls

    private fun publisher(): NotePublisher = NotePublisher(FakeNoteStore(), TestWebPageUrls)

    @Test
    fun `Create に包んだ Note を組み立てる`() {
        val prepared = publisher().prepare(sender, "<p>こんにちは</p>", listOf(), null, listOf())

        val activity = AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson)
        assertEquals("Create", activity.type)
        assertEquals(sender.actorId, activity.actor)
        assertEquals(listOf(ActivityStreamsIri.PUBLIC_AUDIENCE), activity.to)
        assertEquals(listOf(sender.followers), activity.cc)

        assertEquals("Note", activity.target.type)
        assertEquals("<p>こんにちは</p>", activity.target.content)
        assertEquals(sender.actorId, activity.target.attributedTo)
        assertEquals("https://example.com/notes/${prepared.publicId.value}", activity.target.id.value)
        // 相手がパーマリンクとして開くのは画面の URL
        assertEquals("https://example.com/@${TestLocalActor.USERNAME}/${prepared.publicId.value}", activity.target.url)
        // 外側の Create が @context を持つので、中で重ねない
        assertNull(activity.target.context)
    }

    @Test
    fun `id は UUID v7 で時刻と揃う`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", listOf(), null, listOf())

        assertEquals(7, UUID.fromString(prepared.publicId.value).version())
        assertEquals("<p>本文</p>", prepared.contentHtml)
        assertEquals(prepared.publishedAt.toActivityPubPublished(), AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson).published)
    }

    @Test
    fun `組み立てるだけで記録も配信もしない`() {
        val notes = FakeNoteStore()
        val delivery = TestDelivery()
        val publisher = NotePublisher(notes, TestWebPageUrls)

        publisher.prepare(sender, "<p>本文</p>", listOf(), null, listOf())

        assertTrue(notes.added.isEmpty())
        assertTrue(delivery.delivered.isEmpty())
    }

    @Test
    fun `呼ぶたびに別の id になる`() {
        val publisher = publisher()

        val first = publisher.prepare(sender, "<p>1</p>", listOf(), null, listOf())
        val second = publisher.prepare(sender, "<p>2</p>", listOf(), null, listOf())

        assertTrue(first.publicId != second.publicId)
    }

    @Test
    fun `付けた画像を Document として付けた順に載せる`() {
        val prepared = publisher().prepare(
            sender,
            "<p>画像</p>",
            listOf(
                NoteAttachment(url = "https://example.com/media/1.png", mediaType = "image/png", description = "猫"),
                NoteAttachment(url = "https://example.com/media/2.jpg", mediaType = "image/jpeg", description = null),
            ),
            null,
            listOf(),
        )

        val attachment = AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson).target.attachment
        assertEquals(listOf("https://example.com/media/1.png", "https://example.com/media/2.jpg"), attachment.map { it.url })
        assertEquals(listOf("image/png", "image/jpeg"), attachment.map { it.mediaType })
        assertEquals(listOf("猫", null), attachment.map { it.name })
        assertEquals(listOf("Document", "Document"), attachment.map { it.type })
    }

    @Test
    fun `返信先と宛てたアカウントを載せ、宛てたアクターを cc に入れる`() {
        val prepared = publisher().prepare(
            sender,
            "<p>@alice こんにちは</p>",
            listOf(),
            "https://remote.example/notes/1",
            listOf(NoteMention(actorId = "https://remote.example/users/alice", acct = "alice@remote.example")),
        )

        val activity = AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson)
        assertEquals(listOf(sender.followers, "https://remote.example/users/alice"), activity.cc)
        assertEquals(listOf(sender.followers, "https://remote.example/users/alice"), activity.target.cc)
        assertEquals("https://remote.example/notes/1", activity.target.inReplyTo)
        assertEquals(listOf("Mention"), activity.target.tag.map { it.type })
        assertEquals(listOf("https://remote.example/users/alice"), activity.target.tag.map { it.href })
        assertEquals(listOf("@alice@remote.example"), activity.target.tag.map { it.name })
    }
}
