package net.matsudamper.kotpub.webfinger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import net.matsudamper.kotpub.FakeStoredActorNames
import net.matsudamper.kotpub.actor.ActorDirectory
import net.matsudamper.kotpub.actor.InstanceActorUrls
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.json.AppJson

class WebFingerEndpointTest {
    private val endpoint = WebFingerEndpoint(
        directory = ActorDirectory(domain = "example.com", stored = FakeStoredActorNames(mutableListOf("admin"))),
        instanceActor = InstanceActorUrls(domain = "example.com"),
    )

    @Test
    fun `サーバー自身のアクターをドメイン名で引ける`() {
        for (resource in listOf("acct:example.com@example.com", "EXAMPLE.COM@example.com", "https://example.com/actor")) {
            val response = runBlocking { endpoint.get(resource) }

            assertEquals(HttpStatusCodes.OK, response.status, resource)
            val body = AppJson.decodeFromString(WebFingerResponse.serializer(), response.body.decodeToString())
            assertEquals("acct:example.com@example.com", body.subject)
            assertEquals(listOf("https://example.com/actor"), body.links.map { it.href })
        }
    }

    @Test
    fun `ドメインと同じ名前のアカウントがあればアカウントを返す`() {
        val endpoint = WebFingerEndpoint(
            directory = ActorDirectory(domain = "example.com", stored = FakeStoredActorNames(mutableListOf("example.com"))),
            instanceActor = InstanceActorUrls(domain = "example.com"),
        )

        val response = runBlocking { endpoint.get("acct:example.com@example.com") }

        val body = AppJson.decodeFromString(WebFingerResponse.serializer(), response.body.decodeToString())
        assertEquals(listOf("https://example.com/users/example.com"), body.links.map { it.href })
    }

    @Test
    fun `アカウントは今までどおり引ける`() {
        val response = runBlocking { endpoint.get("acct:admin@example.com") }

        val body = AppJson.decodeFromString(WebFingerResponse.serializer(), response.body.decodeToString())
        assertEquals(listOf("https://example.com/users/admin"), body.links.map { it.href })
    }
}
