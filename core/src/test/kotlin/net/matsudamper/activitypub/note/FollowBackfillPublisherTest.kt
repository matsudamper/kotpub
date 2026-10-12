package net.matsudamper.activitypub.note

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.FakeNoteStore
import net.matsudamper.activitypub.TestDelivery
import net.matsudamper.activitypub.TestLocalActor
import net.matsudamper.activitypub.TestRemoteActor
import net.matsudamper.activitypub.TestWebPageUrls
import net.matsudamper.activitypub.activity.CreateNoteActivity
import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.json.AppJson

// フォローが成立した相手に、フォローより前の投稿を配るところ。
// 成立した時点より前の投稿だけを、古い順に、上限まで配るのがこの publisher の肝になる。
class FollowBackfillPublisherTest {
    private val sender = TestLocalActor.urls

    private val followAcceptedAt: Instant = Instant.parse("2026-08-10T12:00:00Z")

    private fun publisher(
        notes: FakeNoteStore,
        delivery: TestDelivery,
    ): FollowBackfillPublisher = FollowBackfillPublisher(
        notes = notes,
        delivery = delivery,
        webPages = TestWebPageUrls,
    )

    /**
     * フォローより前の投稿を [count] 件持たせる
     */
    private fun notesOf(count: Int): FakeNoteStore = FakeNoteStore().apply {
        repeat(count) { index ->
            add(
                StoredNote(
                    publicId = PublicNoteId("note-%02d".format(index)),
                    username = TestLocalActor.USERNAME,
                    contentHtml = "<p>$index</p>",
                    publishedAt = Instant.parse("2026-08-10T00:00:00Z").plusSeconds(index.toLong()),
                ),
            )
        }
    }

    private fun deliveredCreates(delivery: TestDelivery): List<CreateNoteActivity> = delivery.delivered
        .map { AppJson.parseToJsonElement(it.body) as JsonObject }
        .map { AppJson.decodeFromJsonElement(CreateNoteActivity.serializer(), it) }

    private suspend fun deliver(
        notes: FakeNoteStore,
        delivery: TestDelivery,
    ) {
        publisher(notes = notes, delivery = delivery).deliverRecentNotes(
            sender = sender,
            inbox = TestRemoteActor.INBOX,
            publishedBefore = followAcceptedAt,
        )
    }

    @Test
    fun `フォローより前の投稿を古い順に配る`() = runBlocking {
        val delivery = TestDelivery()

        deliver(notes = notesOf(3), delivery = delivery)

        assertEquals(listOf(TestRemoteActor.INBOX), delivery.delivered.map { it.inbox }.distinct())
        assertEquals(listOf("<p>0</p>", "<p>1</p>", "<p>2</p>"), deliveredCreates(delivery).map { it.target.content })
    }

    @Test
    fun `配り直した Create は元の投稿と同じ id と日時になる`() = runBlocking {
        val delivery = TestDelivery()
        val notes = notesOf(1)

        deliver(notes = notes, delivery = delivery)

        // 相手はこの id で重複を判断する。作り直すとタイムラインに 2 度並ぶ
        val note = notes.added.single()
        val create = deliveredCreates(delivery).single()
        assertEquals("https://${TestLocalActor.DOMAIN}/notes/${note.publicId.value}#create", create.id.value)
        assertEquals("https://${TestLocalActor.DOMAIN}/notes/${note.publicId.value}", create.target.id.value)
        assertEquals(create.published, create.target.published)
        assertEquals("2026-08-10T00:00:00Z", create.published)
    }

    @Test
    fun `配る件数には上限がある`() = runBlocking {
        val delivery = TestDelivery()

        deliver(notes = notesOf(25), delivery = delivery)

        val creates = deliveredCreates(delivery)
        assertEquals(5, creates.size)
        // 上限を超えるときは新しい方を残す
        assertEquals("<p>24</p>", creates.last().target.content)
        assertEquals("<p>20</p>", creates.first().target.content)
    }

    @Test
    fun `フォローが成立した後に作られた投稿は配らない`() = runBlocking {
        val delivery = TestDelivery()
        val notes = notesOf(1).apply {
            add(
                StoredNote(
                    publicId = PublicNoteId("note-after"),
                    username = TestLocalActor.USERNAME,
                    contentHtml = "<p>フォローの後</p>",
                    publishedAt = followAcceptedAt.plusSeconds(60),
                ),
            )
        }

        deliver(notes = notes, delivery = delivery)

        // 成立後の投稿は通常の配信で届く。混ぜるとフォロー前の投稿が上限から押し出される
        assertEquals(listOf("<p>0</p>"), deliveredCreates(delivery).map { it.target.content })
    }

    @Test
    fun `成立後の投稿があっても上限まで過去の投稿を配る`() = runBlocking {
        val delivery = TestDelivery()
        val notes = notesOf(20).apply {
            repeat(5) { index ->
                add(
                    StoredNote(
                        publicId = PublicNoteId("note-after-$index"),
                        username = TestLocalActor.USERNAME,
                        contentHtml = "<p>フォローの後 $index</p>",
                        publishedAt = followAcceptedAt.plusSeconds(index + 1L),
                    ),
                )
            }
        }

        deliver(notes = notes, delivery = delivery)

        // 成立後の投稿を数に含めて切ると、その分だけ過去の投稿が減る
        assertEquals(5, deliveredCreates(delivery).size)
    }
}
