package net.matsudamper.kotpub.webfinger

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse
import net.matsudamper.kotpub.http.HttpStatusCodes

/**
 * acct から WebFinger の URL を組み立てる部分と、応答からアクターの id を選ぶ部分。通信は差し替える
 */
class HttpWebFingerLookupTest {
    @Test
    fun `acctのホストへWebFingerを問い合わせてアクターのidを返す`() {
        val client = RespondingClient(body = webFingerBody(href = "https://example.com/users/alice"))

        val actorId = runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }

        assertEquals("https://example.com/users/alice", actorId)
        assertEquals("https://example.com/.well-known/webfinger?resource=acct%3Aalice%40example.com", client.requestedUrls.single())
    }

    @Test
    fun `先頭の@やacctの接頭辞は無くてもよい`() {
        val client = RespondingClient(body = webFingerBody(href = "https://example.com/users/alice"))

        runBlocking {
            HttpWebFingerLookup(client).findActorId("alice@example.com")
            HttpWebFingerLookup(client).findActorId("acct:alice@example.com")
        }

        assertEquals(
            listOf(
                "https://example.com/.well-known/webfinger?resource=acct%3Aalice%40example.com",
                "https://example.com/.well-known/webfinger?resource=acct%3Aalice%40example.com",
            ),
            client.requestedUrls,
        )
    }

    @Test
    fun `IDNのホストはpunycodeで問い合わせる`() {
        val client = RespondingClient(body = webFingerBody(href = "https://xn--r8jz45g.example/users/alice"))

        runBlocking { HttpWebFingerLookup(client).findActorId("@alice@例え.example") }

        assertEquals(
            "https://xn--r8jz45g.example/.well-known/webfinger?resource=acct%3Aalice%40xn--r8jz45g.example",
            client.requestedUrls.single(),
        )
    }

    @Test
    fun `acctとして読めない入力では問い合わせない`() {
        val client = RespondingClient(body = webFingerBody(href = "https://example.com/users/alice"))

        val results =
            runBlocking {
                listOf("alice", "@alice", "@alice@", "@@example.com", "@alice@example.com/evil", "@alice@example.com?x=1", "@al ice@example.com")
                    .map { HttpWebFingerLookup(client).findActorId(it) }
            }

        assertEquals(listOf(null, null, null, null, null, null, null), results)
        assertEquals(listOf(), client.requestedUrls)
    }

    @Test
    fun `IDNの正規化で区切り文字になる全角文字を含むホストでは問い合わせない`() {
        val client = RespondingClient(body = webFingerBody(href = "https://example.com/users/alice"))

        val actorId = runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com／admin＃") }

        assertNull(actorId)
        assertEquals(listOf(), client.requestedUrls)
    }

    @Test
    fun `呼び出し元のキャンセルは握りつぶさない`() {
        val client = CancellingClient()

        assertFailsWith<CancellationException> {
            runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }
        }
    }

    @Test
    fun `アクター文書の型のselfリンクだけを選ぶ`() {
        val client =
            RespondingClient(
                body =
                """
                {
                  "subject": "acct:alice@example.com",
                  "links": [
                    {"rel": "http://webfinger.net/rel/profile-page", "type": "text/html", "href": "https://example.com/@alice"},
                    {"rel": "self", "type": "text/html", "href": "https://example.com/@alice"},
                    {"rel": "self", "type": "application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\"", "href": "https://example.com/users/alice"}
                  ]
                }
                """.trimIndent(),
            )

        val actorId = runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }

        assertEquals("https://example.com/users/alice", actorId)
    }

    @Test
    fun `httpsでないアクターのidは返さない`() {
        val client = RespondingClient(body = webFingerBody(href = "http://example.com/users/alice"))

        assertNull(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") })
    }

    @Test
    fun `成功でない応答では返さない`() {
        val client = RespondingClient(body = webFingerBody(href = "https://example.com/users/alice"), status = HttpStatusCodes.NOT_FOUND)

        assertNull(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") })
    }

    @Test
    fun `別ホストへのリダイレクト先から取得したものは使う`() {
        val client =
            RespondingClient(
                body = webFingerBody(href = "https://social.example.com/users/alice"),
                finalUrl = "https://social.example.com/.well-known/webfinger?resource=acct%3Aalice%40example.com",
            )

        val actorId = runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }

        assertEquals("https://social.example.com/users/alice", actorId)
    }

    @Test
    fun `httpsでない先へリダイレクトされたものは使わない`() {
        val client =
            RespondingClient(
                body = webFingerBody(href = "https://example.com/users/alice"),
                finalUrl = "http://example.com/.well-known/webfinger?resource=acct%3Aalice%40example.com",
            )

        assertNull(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") })
    }

    @Test
    fun `届かなければnullを返す`() {
        val client = FailingClient()

        assertNull(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") })
    }

    @Test
    fun `JSONとして読めなければnullを返す`() {
        val client = RespondingClient(body = "<html></html>")

        assertNull(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") })
    }

    private fun webFingerBody(href: String): String =
        """
        {
          "subject": "acct:alice@example.com",
          "links": [
            {"rel": "self", "type": "application/activity+json", "href": "$href"}
          ]
        }
        """.trimIndent()

    /**
     * @param finalUrl null なら要求された URL のまま返す
     */
    private class RespondingClient(
        private val body: String,
        private val status: Int = HttpStatusCodes.OK,
        private val finalUrl: String? = null,
    ) : ActivityPubHttpClient {
        val requestedUrls: MutableList<String> = mutableListOf()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBodyBytes: Int,
            readsBody: (status: Int, finalUrl: String) -> Boolean,
        ): ActivityPubClientResponse {
            requestedUrls += url
            val respondedUrl = finalUrl ?: url
            return ActivityPubClientResponse(
                status = status,
                finalUrl = respondedUrl,
                body = if (readsBody(status, respondedUrl)) Result.success(body.toByteArray()) else null,
            )
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
        ): ActivityPubPostResponse = error("WebFinger では POST しない")

        override fun close() = Unit
    }

    private class CancellingClient : ActivityPubHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBodyBytes: Int,
            readsBody: (status: Int, finalUrl: String) -> Boolean,
        ): ActivityPubClientResponse = throw CancellationException("呼び出し元がキャンセルした")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
        ): ActivityPubPostResponse = error("WebFinger では POST しない")

        override fun close() = Unit
    }

    private class FailingClient : ActivityPubHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            maxBodyBytes: Int,
            readsBody: (status: Int, finalUrl: String) -> Boolean,
        ): ActivityPubClientResponse = throw IOException("接続できない")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
        ): ActivityPubPostResponse = error("WebFinger では POST しない")

        override fun close() = Unit
    }
}
