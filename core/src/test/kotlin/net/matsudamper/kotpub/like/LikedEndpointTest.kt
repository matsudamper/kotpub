package net.matsudamper.kotpub.like

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.serializer
import net.matsudamper.kotpub.TestLocalActor
import net.matsudamper.kotpub.collection.COLLECTION_PAGE_SIZE
import net.matsudamper.kotpub.collection.OrderedCollection
import net.matsudamper.kotpub.collection.OrderedCollectionPage
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.json.AppJson

class LikedEndpointTest {
    private val requestedAfter = mutableListOf<LikedPosition?>()

    private fun endpoint(liked: List<LikedObject>): LikedEndpoint = LikedEndpoint(
        directory = TestLocalActor.directory,
        profiles = TestLocalActor.profiles,
        likedObjects = object : LikedObjectStore {
            override fun list(username: String, after: LikedPosition?, limit: Int): List<LikedObject> {
                requestedAfter.add(after)
                return liked.take(limit)
            }

            override fun count(username: String): Long = liked.size.toLong()
        },
    )

    private fun liked(index: Int): LikedObject = LikedObject(
        objectUri = "https://remote.example/notes/$index",
        position = LikedPosition(at = Instant.parse("2026-11-01T00:00:00Z").minusSeconds(index.toLong()), key = "l$index"),
    )

    @Test
    fun `パラメータが無ければ総数と最初のページを返す`() {
        val response = endpoint(listOf(liked(1))).get(username = TestLocalActor.STORED_USERNAME, accept = null, cursor = null)

        assertEquals(HttpStatusCodes.OK, response.status)
        val collection = AppJson.decodeFromString(OrderedCollection.serializer(), response.body.decodeToString())
        assertEquals("https://example.com/users/${TestLocalActor.STORED_USERNAME}/liked", collection.id)
        assertEquals(1L, collection.totalItems)
        assertEquals("https://example.com/users/${TestLocalActor.STORED_USERNAME}/liked?cursor=", collection.first)
    }

    @Test
    fun `ページには投稿の id を並べ 埋まったら続きを指す`() {
        val all = (1..COLLECTION_PAGE_SIZE).map { liked(it) }
        val response = endpoint(all).get(username = TestLocalActor.STORED_USERNAME, accept = null, cursor = "")

        val page = AppJson.decodeFromString(OrderedCollectionPage.serializer(String.serializer()), response.body.decodeToString())
        assertEquals(all.map { it.objectUri }, page.orderedItems)
        val last = all.last().position
        assertEquals(
            "https://example.com/users/${TestLocalActor.STORED_USERNAME}/liked?cursor=${last.at.epochSecond}_${last.at.nano}_${last.key}",
            page.next,
        )
    }

    @Test
    fun `続きの cursor から位置を読む`() {
        val position = liked(3).position
        endpoint(listOf()).get(username = TestLocalActor.STORED_USERNAME, accept = null, cursor = "${position.at.epochSecond}_${position.at.nano}_${position.key}")

        assertEquals(listOf<LikedPosition?>(position), requestedAfter)
    }

    @Test
    fun `key が空の位置も続きとして読む`() {
        val position = LikedPosition(at = Instant.parse("2026-11-01T00:00:00Z"), key = "")
        endpoint(listOf()).get(username = TestLocalActor.STORED_USERNAME, accept = null, cursor = "${position.at.epochSecond}_${position.at.nano}_")

        assertEquals(listOf<LikedPosition?>(position), requestedAfter)
    }

    @Test
    fun `公開していないアカウントは 404`() {
        assertEquals(HttpStatusCodes.NOT_FOUND, endpoint(listOf(liked(1))).get(username = TestLocalActor.USERNAME, accept = null, cursor = null).status)
    }

    @Test
    fun `知らないアカウントは 404`() {
        assertEquals(HttpStatusCodes.NOT_FOUND, endpoint(listOf(liked(1))).get(username = "other", accept = null, cursor = null).status)
    }
}
