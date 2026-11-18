package net.matsudamper.kotpub.note

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.matsudamper.kotpub.FakeFollowerStore
import net.matsudamper.kotpub.FakeNoteStore
import net.matsudamper.kotpub.TestDelivery
import net.matsudamper.kotpub.TestLocalActor
import net.matsudamper.kotpub.TestWebPageUrls
import net.matsudamper.kotpub.activity.ActivityStreamsIri
import net.matsudamper.kotpub.activity.CreateNoteActivity
import net.matsudamper.kotpub.emoji.EmojiObject
import net.matsudamper.kotpub.json.AppJson

// フォロワーに送る Create{Note} を組み立てるところ。
// 記録も配信もここではしない。組み立てた JSON が Mastodon に通る形かどうかが肝。
class NotePublisherTest {
    private val sender = TestLocalActor.urls

    private fun publisher(): NotePublisher = NotePublisher(FakeNoteStore(), TestWebPageUrls)

    @Test
    fun `Create に包んだ Note を組み立てる`() {
        val prepared = publisher().prepare(sender, "<p>こんにちは</p>", listOf(), null, null, listOf(), listOf(), false)

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
        val prepared = publisher().prepare(sender, "<p>本文</p>", listOf(), null, null, listOf(), listOf(), false)

        assertEquals(7, UUID.fromString(prepared.publicId.value).version())
        assertEquals("<p>本文</p>", prepared.contentHtml)
        assertEquals(prepared.publishedAt.toActivityPubPublished(), AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson).published)
    }

    @Test
    fun `組み立てるだけで記録も配信もしない`() {
        val notes = FakeNoteStore()
        val delivery = TestDelivery()
        val publisher = NotePublisher(notes, TestWebPageUrls)

        publisher.prepare(sender, "<p>本文</p>", listOf(), null, null, listOf(), listOf(), false)

        assertTrue(notes.added.isEmpty())
        assertTrue(delivery.delivered.isEmpty())
    }

    @Test
    fun `呼ぶたびに別の id になる`() {
        val publisher = publisher()

        val first = publisher.prepare(sender, "<p>1</p>", listOf(), null, null, listOf(), listOf(), false)
        val second = publisher.prepare(sender, "<p>2</p>", listOf(), null, null, listOf(), listOf(), false)

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
            null,
            listOf(),
            listOf(),
            false,
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
            null,
            listOf(NoteMention(actorId = "https://remote.example/users/alice", acct = "alice@remote.example")),
            listOf(),
            false,
        )

        val activity = AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson)
        assertEquals(listOf(sender.followers, "https://remote.example/users/alice"), activity.cc)
        assertEquals(listOf(sender.followers, "https://remote.example/users/alice"), activity.target.cc)
        assertEquals("https://remote.example/notes/1", activity.target.inReplyTo)
        assertEquals(
            listOf<NoteTag>(NoteMentionTag(href = "https://remote.example/users/alice", name = "@alice@remote.example")),
            activity.target.tag,
        )
    }

    @Test
    fun `本文のカスタム絵文字を Emoji として載せる`() {
        val icon = EmojiObject.Icon(url = "https://example.com/custom-emojis/1", mediaType = "image/png")
        val prepared = publisher().prepare(
            sender,
            "<p>:blobcat:</p>",
            listOf(),
            null,
            null,
            listOf(),
            listOf(EmojiObject(id = "https://example.com/emojis/1", name = ":blobcat:", icon = icon)),
            false,
        )

        val tag = AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson).target.tag
        assertEquals(listOf<NoteTag>(NoteEmojiTag(id = "https://example.com/emojis/1", name = ":blobcat:", icon = icon)), tag)
        assertEquals(listOf("Emoji"), tag.map { it.type })
    }

    @Test
    fun `閲覧注意の投稿は sensitive を付ける`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", listOf(), null, null, listOf(), listOf(), true)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        assertEquals(true, note.getValue("sensitive").jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun `引用した投稿を各実装が読む名前で載せる`() {
        val prepared = publisher().prepare(sender, "<p>引用</p>", listOf(), null, "https://remote.example/notes/2", listOf(), listOf(), false)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        assertEquals(
            listOf("https://remote.example/notes/2"),
            listOf("quote", "quoteUrl", "_misskey_quote").map { note.getValue(it).jsonPrimitive.content }.distinct(),
        )
    }

    @Test
    fun `引用でなければ引用のキーを出さない`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", listOf(), null, null, listOf(), listOf(), false)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        assertEquals(listOf(), listOf("quote", "quoteUrl", "_misskey_quote").filter { it in note })
    }

    @Test
    fun `誰でも引用できることを interactionPolicy で示す`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", listOf(), null, null, listOf(), listOf(), false)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        val automaticApproval = note.getValue("interactionPolicy").jsonObject
            .getValue("canQuote").jsonObject
            .getValue("automaticApproval").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf(ActivityStreamsIri.PUBLIC_AUDIENCE), automaticApproval)
    }
}
