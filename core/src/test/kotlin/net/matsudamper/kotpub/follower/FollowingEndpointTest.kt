package net.matsudamper.kotpub.follower

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import net.matsudamper.kotpub.TestLocalActor
import net.matsudamper.kotpub.collection.COLLECTION_PAGE_SIZE
import net.matsudamper.kotpub.collection.OrderedCollection
import net.matsudamper.kotpub.collection.OrderedCollectionPage
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.QueryParameter
import net.matsudamper.kotpub.json.AppJson

class FollowingEndpointTest {
    private val requestedAfter = mutableListOf<String?>()

    private fun get(following: List<String>, username: String?, cursor: String?): EndpointResponse {
        val endpoint = FollowingEndpoint(
            directory = TestLocalActor.directory,
            following = object : FollowingStore {
                override fun list(username: String, after: String?, limit: Int): List<String> {
                    requestedAfter.add(after)
                    return following.take(limit)
                }

                override fun count(username: String): Long = following.size.toLong()
            },
        )
        return runBlocking { endpoint.get(username = username, accept = null, cursor = cursor) }
    }

    private fun actor(index: Int): String = "https://remote.example/users/u$index"

    @Test
    fun `パラメータが無ければ総数と最初のページを返す`() {
        val response = get(following = listOf(actor(1)), username = TestLocalActor.USERNAME, cursor = null)

        assertEquals(HttpStatusCodes.OK, response.status)
        val collection = AppJson.decodeFromString(OrderedCollection.serializer(), response.body.decodeToString())
        assertEquals(TestLocalActor.urls.following, collection.id)
        assertEquals(1L, collection.totalItems)
        assertEquals("${TestLocalActor.urls.following}?cursor=", collection.first)
    }

    @Test
    fun `ページにはアクターを並べ 埋まったら最後のアクターから続きを指す`() {
        val all = (1..COLLECTION_PAGE_SIZE).map { actor(it) }
        val response = get(following = all, username = TestLocalActor.USERNAME, cursor = "")

        val page = AppJson.decodeFromString(OrderedCollectionPage.serializer(String.serializer()), response.body.decodeToString())
        assertEquals(all, page.orderedItems)
        assertEquals(TestLocalActor.urls.following, page.partOf)
        assertEquals("${TestLocalActor.urls.following}?cursor=${QueryParameter.encode(all.last())}", page.next)
        assertEquals(listOf<String?>(null), requestedAfter)
    }

    @Test
    fun `埋まらなければ続きを指さない`() {
        val response = get(following = listOf(actor(1)), username = TestLocalActor.USERNAME, cursor = actor(0))

        val page = AppJson.decodeFromString(OrderedCollectionPage.serializer(String.serializer()), response.body.decodeToString())
        assertNull(page.next)
        assertEquals(listOf<String?>(actor(0)), requestedAfter)
    }

    @Test
    fun `知らないアカウントは 404`() {
        assertEquals(HttpStatusCodes.NOT_FOUND, get(following = listOf(), username = "unknown", cursor = null).status)
    }
}
