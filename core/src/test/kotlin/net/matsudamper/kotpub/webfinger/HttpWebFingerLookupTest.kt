package net.matsudamper.kotpub.webfinger

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.RemoteFetchFailure
import net.matsudamper.kotpub.http.ResponseBody

/**
 * acct から WebFinger の URL を組み立てる部分と、応答からアクターの id を選ぶ部分。通信は差し替える
 */
class HttpWebFingerLookupTest {
    @Test
    fun `acctのホストへWebFingerを問い合わせてアクターのidを返す`() {
        val client = RespondingClient(body = webFingerBody(href = "https://example.com/users/alice"))

        val actorId = runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }

        assertEquals(WebFingerResult.Found("https://example.com/users/alice"), actorId)
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

        assertEquals(List(7) { WebFingerResult.InvalidAcct }, results)
        assertEquals(listOf(), client.requestedUrls)
    }

    @Test
    fun `IDNの正規化で区切り文字になる全角文字を含むホストでは問い合わせない`() {
        val client = RespondingClient(body = webFingerBody(href = "https://example.com/users/alice"))

        val actorId = runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com／admin＃") }

        assertEquals(WebFingerResult.InvalidAcct, actorId)
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

        assertEquals(WebFingerResult.Found("https://example.com/users/alice"), actorId)
    }

    @Test
    fun `httpsでないアクターのidは返さない`() {
        val client = RespondingClient(body = webFingerBody(href = "http://example.com/users/alice"))

        assertIs<RemoteFetchFailure.InvalidDocument>(assertIs<WebFingerResult.NotFound>(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }).failure)
    }

    @Test
    fun `成功でない応答では返さない`() {
        val client = RespondingClient(body = webFingerBody(href = "https://example.com/users/alice"), status = HttpStatusCodes.NOT_FOUND)

        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<WebFingerResult.NotFound>(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }).failure)
    }

    @Test
    fun `相手が5xxを返したら今は引けないと返す`() {
        val client = RespondingClient(body = "", status = 503)

        assertIs<RemoteFetchFailure.UnsuccessfulStatus>(assertIs<WebFingerResult.Unavailable>(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }).failure)
    }

    @Test
    fun `別ホストへのリダイレクト先から取得したものは使う`() {
        val client =
            RespondingClient(
                body = webFingerBody(href = "https://social.example.com/users/alice"),
                finalUrl = "https://social.example.com/.well-known/webfinger?resource=acct%3Aalice%40example.com",
            )

        val actorId = runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }

        assertEquals(WebFingerResult.Found("https://social.example.com/users/alice"), actorId)
    }

    @Test
    fun `httpsでない先へリダイレクトされたものは使わない`() {
        val client =
            RespondingClient(
                body = webFingerBody(href = "https://example.com/users/alice"),
                finalUrl = "http://example.com/.well-known/webfinger?resource=acct%3Aalice%40example.com",
            )

        assertIs<RemoteFetchFailure.RedirectedToDisallowedUrl>(assertIs<WebFingerResult.NotFound>(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }).failure)
    }

    @Test
    fun `届かなければ今は引けないと返す`() {
        val client = FailingClient()

        assertIs<RemoteFetchFailure.RequestFailed>(assertIs<WebFingerResult.Unavailable>(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }).failure)
    }

    @Test
    fun `JSONとして読めなければ見つからないと返す`() {
        val client = RespondingClient(body = "<html></html>")

        assertIs<RemoteFetchFailure.InvalidDocument>(assertIs<WebFingerResult.NotFound>(runBlocking { HttpWebFingerLookup(client).findActorId("@alice@example.com") }).failure)
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
                body = if (readsBody(status, respondedUrl)) ResponseBody.Read(body.toByteArray()) else ResponseBody.NotRead,
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
