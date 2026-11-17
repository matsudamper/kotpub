package net.matsudamper.kotpub.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.matsudamper.kotpub.TestActorPrivateKey
import net.matsudamper.kotpub.json.AppJson

class InstanceActorEndpointTest {
    private val actor = InstanceActor(
        urls = InstanceActorUrls(domain = "example.com"),
        privateKey = TestActorPrivateKey.value,
    )

    @Test
    fun `署名の keyId から公開鍵を引けるアクターを返す`() {
        val response = InstanceActorEndpoint(actor).get(accept = "application/activity+json")

        val document = AppJson.parseToJsonElement(response.body.decodeToString()).jsonObject
        assertEquals("https://example.com/actor", document["id"]?.jsonPrimitive?.content)
        assertEquals("Application", document["type"]?.jsonPrimitive?.content)
        assertEquals("example.com", document["preferredUsername"]?.jsonPrimitive?.content)
        val publicKey = document["publicKey"]?.jsonObject
        assertEquals("https://example.com/actor#main-key", publicKey?.get("id")?.jsonPrimitive?.content)
        assertEquals("https://example.com/actor", publicKey?.get("owner")?.jsonPrimitive?.content)
        assertEquals(TestActorPrivateKey.value.publicKeyPem, publicKey?.get("publicKeyPem")?.jsonPrimitive?.content)
        assertEquals("application/activity+json", response.contentType)
    }
}
