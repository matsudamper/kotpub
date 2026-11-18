package net.matsudamper.kotpub.note

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import net.matsudamper.kotpub.activity.UpdateNoteActivity
import net.matsudamper.kotpub.emoji.EmojiObject
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.json.AppJson

// フォロワーに送る Create{Note} を組み立てるところ。
// 記録も配信もここではしない。組み立てた JSON が Mastodon に通る形かどうかが肝。
class NotePublisherTest {
    private val sender = TestLocalActor.urls

    private fun publisher(): NotePublisher = NotePublisher(FakeNoteStore(), TestWebPageUrls)

    @Test
    fun `Create に包んだ Note を組み立てる`() {
        val prepared = publisher().prepare(sender, "<p>こんにちは</p>", null, listOf(), null, null, listOf(), listOf(), false, null)

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
        val prepared = publisher().prepare(sender, "<p>本文</p>", null, listOf(), null, null, listOf(), listOf(), false, null)

        assertEquals(7, UUID.fromString(prepared.publicId.value).version())
        assertEquals("<p>本文</p>", prepared.contentHtml)
        assertEquals(prepared.publishedAt.toActivityPubPublished(), AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson).published)
    }

    @Test
    fun `組み立てるだけで記録も配信もしない`() {
        val notes = FakeNoteStore()
        val delivery = TestDelivery()
        val publisher = NotePublisher(notes, TestWebPageUrls)

        publisher.prepare(sender, "<p>本文</p>", null, listOf(), null, null, listOf(), listOf(), false, null)

        assertTrue(notes.added.isEmpty())
        assertTrue(delivery.delivered.isEmpty())
    }

    @Test
    fun `呼ぶたびに別の id になる`() {
        val publisher = publisher()

        val first = publisher.prepare(sender, "<p>1</p>", null, listOf(), null, null, listOf(), listOf(), false, null)
        val second = publisher.prepare(sender, "<p>2</p>", null, listOf(), null, null, listOf(), listOf(), false, null)

        assertTrue(first.publicId != second.publicId)
    }

    @Test
    fun `付けた画像を Document として付けた順に載せる`() {
        val prepared = publisher().prepare(
            sender,
            "<p>画像</p>",
            null,
            listOf(
                NoteAttachment(url = "https://example.com/media/1.png", mediaType = "image/png", description = "猫"),
                NoteAttachment(url = "https://example.com/media/2.jpg", mediaType = "image/jpeg", description = null),
            ),
            null,
            null,
            listOf(),
            listOf(),
            false,
            null,
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
            null,
            listOf(),
            "https://remote.example/notes/1",
            null,
            listOf(NoteMention(actorId = "https://remote.example/users/alice", acct = "alice@remote.example")),
            listOf(),
            false,
            null,
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
            null,
            listOf(),
            null,
            null,
            listOf(),
            listOf(EmojiObject(id = "https://example.com/emojis/1", name = ":blobcat:", icon = icon)),
            false,
            null,
        )

        val tag = AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson).target.tag
        assertEquals(listOf<NoteTag>(NoteEmojiTag(id = "https://example.com/emojis/1", name = ":blobcat:", icon = icon)), tag)
        assertEquals(listOf("Emoji"), tag.map { it.type })
    }

    @Test
    fun `閲覧注意の投稿は sensitive を付ける`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", null, listOf(), null, null, listOf(), listOf(), true, null)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        assertEquals(true, note.getValue("sensitive").jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun `引用した投稿を各実装が読む名前で載せる`() {
        val prepared = publisher().prepare(sender, "<p>引用</p>", null, listOf(), null, "https://remote.example/notes/2", listOf(), listOf(), false, null)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        assertEquals(
            listOf("https://remote.example/notes/2"),
            listOf("quote", "quoteUrl", "_misskey_quote").map { note.getValue(it).jsonPrimitive.content }.distinct(),
        )
    }

    @Test
    fun `引用でなければ引用のキーを出さない`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", null, listOf(), null, null, listOf(), listOf(), false, null)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        assertEquals(listOf(), listOf("quote", "quoteUrl", "_misskey_quote").filter { it in note })
    }

    @Test
    fun `誰でも引用できることを interactionPolicy で示す`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", null, listOf(), null, null, listOf(), listOf(), false, null)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        val automaticApproval = note.getValue("interactionPolicy").jsonObject
            .getValue("canQuote").jsonObject
            .getValue("automaticApproval").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf(ActivityStreamsIri.PUBLIC_AUDIENCE), automaticApproval)
    }

    @Test
    fun `閲覧注意の注意書きを summary に載せ、sensitive にする`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", "ネタバレ", listOf(), null, null, listOf(), listOf(), false, null)

        val note = AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson).target
        assertEquals("ネタバレ", note.summary)
        assertTrue(note.sensitive)
    }

    @Test
    fun `閲覧注意でなければ sensitive にしない`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", null, listOf(), null, null, listOf(), listOf(), false, null)

        val note = AppJson.decodeFromString(CreateNoteActivity.serializer(), prepared.activityJson).target
        assertNull(note.summary)
        assertEquals(false, note.sensitive)
    }

    @Test
    fun `複数選べる投票は anyOf に選択肢を並べ、Question にする`() {
        val poll = NotePoll(
            options = listOf(NotePollOption(name = "犬", votesCount = 0), NotePollOption(name = "猫", votesCount = 0)),
            multipleChoice = true,
            endsAt = Instant.parse("2026-01-02T00:00:00Z"),
            votersCount = 0,
        )
        val prepared = publisher().prepare(sender, "<p>どっち</p>", null, listOf(), null, null, listOf(), listOf(), false, poll)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        assertEquals("Question", note.getValue("type").jsonPrimitive.content)
        assertNull(note["oneOf"])
        assertEquals(
            listOf("犬", "猫"),
            note.getValue("anyOf").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        assertEquals("2026-01-02T00:00:00Z", note.getValue("endTime").jsonPrimitive.content)
        assertEquals(0, note.getValue("votersCount").jsonPrimitive.content.toInt())
    }

    @Test
    fun `投票でなければ Note のままにし、投票の項目を載せない`() {
        val prepared = publisher().prepare(sender, "<p>本文</p>", null, listOf(), null, null, listOf(), listOf(), false, null)

        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        assertEquals("Note", note.getValue("type").jsonPrimitive.content)
        assertNull(note["oneOf"])
        assertNull(note["anyOf"])
        assertNull(note["endTime"])
    }

    @Test
    fun `書き換えた本文を Update に包み、元の公開日時と書き換えた日時を載せる`() {
        val publishedAt = Instant.parse("2026-01-01T00:00:00Z")
        val notes = FakeNoteStore().apply { add(recordedNote(publishedAt = publishedAt, updatedAt = null)) }

        val prepared = NotePublisher(notes, TestWebPageUrls).prepareUpdate(sender, PublicNoteId("note-1"), "<p>書き換えた</p>", listOf())

        assertNotNull(prepared)
        val activity = AppJson.decodeFromString(UpdateNoteActivity.serializer(), prepared.activityJson)
        assertEquals("Update", activity.type)
        assertEquals("https://example.com/notes/note-1", activity.target.id.value)
        assertEquals("<p>書き換えた</p>", activity.target.content)
        assertEquals(publishedAt.toActivityPubPublished(), activity.target.published)
        assertEquals(prepared.updatedAt.toActivityPubPublished(), activity.target.updated)
        assertEquals(listOf(sender.followers), activity.cc)
    }

    @Test
    fun `同じ秒に書き換えても updated と id を前より進める`() {
        val previousUpdatedAt = Instant.now().plusSeconds(60)
        val notes = FakeNoteStore().apply { add(recordedNote(publishedAt = Instant.parse("2026-01-01T00:00:00Z"), updatedAt = previousUpdatedAt)) }

        val prepared = NotePublisher(notes, TestWebPageUrls).prepareUpdate(sender, PublicNoteId("note-1"), "<p>本文</p>", listOf())

        assertNotNull(prepared)
        assertTrue(prepared.updatedAt.isAfter(previousUpdatedAt))
        val activity = AppJson.decodeFromString(UpdateNoteActivity.serializer(), prepared.activityJson)
        assertEquals("https://example.com/notes/note-1#updates/${prepared.updatedAt.toEpochMilli()}", activity.id.value)
    }

    @Test
    fun `閲覧注意の投稿を書き換えても sensitive を保つ`() {
        val notes = FakeNoteStore().apply { add(recordedNote(publishedAt = Instant.parse("2026-01-01T00:00:00Z"), updatedAt = null).copy(sensitive = true)) }

        val prepared = NotePublisher(notes, TestWebPageUrls).prepareUpdate(sender, PublicNoteId("note-1"), "<p>本文</p>", listOf())

        assertNotNull(prepared)
        val note = AppJson.parseToJsonElement(prepared.activityJson).jsonObject.getValue("object").jsonObject
        assertEquals(true, note.getValue("sensitive").jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun `書き換えても閲覧注意の注意書きを残す`() {
        val notes = FakeNoteStore().apply { add(recordedNote(publishedAt = Instant.parse("2026-01-01T00:00:00Z"), updatedAt = null).copy(summary = "ネタバレ")) }

        val prepared = NotePublisher(notes, TestWebPageUrls).prepareUpdate(sender, PublicNoteId("note-1"), "<p>書き換えた</p>", listOf())

        assertNotNull(prepared)
        val note = AppJson.decodeFromString(UpdateNoteActivity.serializer(), prepared.activityJson).target
        assertEquals("ネタバレ", note.summary)
        assertTrue(note.sensitive)
    }

    @Test
    fun `別のアカウントの投稿は書き換えられない`() {
        val notes = FakeNoteStore().apply { add(recordedNote(publishedAt = Instant.parse("2026-01-01T00:00:00Z"), updatedAt = null).copy(username = "other")) }

        assertNull(NotePublisher(notes, TestWebPageUrls).prepareUpdate(sender, PublicNoteId("note-1"), "<p>本文</p>", listOf()))
    }

    private fun recordedNote(
        publishedAt: Instant,
        updatedAt: Instant?,
    ): StoredNote = StoredNote(
        publicId = PublicNoteId("note-1"),
        username = TestLocalActor.USERNAME,
        contentHtml = "<p>元の本文</p>",
        summary = null,
        publishedAt = publishedAt,
        updatedAt = updatedAt,
        attachments = listOf(),
        inReplyTo = null,
        quote = null,
        mentions = listOf(),
        emojis = listOf(),
        sensitive = false,
        poll = null,
    )
}
