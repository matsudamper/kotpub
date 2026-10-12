package net.matsudamper.activitypub.activitypub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.activitypub.json.AppJson

class ActorContextSerializerTest {
    @Serializable
    private data class Sample(
        @SerialName("@context")
        @Serializable(with = ActorContextSerializer::class)
        val context: ActorContext = ActorContext,
    )

    @Test
    fun `featured と showFeatured と PropertyValue の語彙を載せる`() {
        val encoded = AppJson.encodeToString(Sample())

        val expected =
            """{"@context":["https://www.w3.org/ns/activitystreams",""" +
                """"https://w3id.org/security/v1",""" +
                """{"toot":"http://joinmastodon.org/ns#",""" +
                """"featured":{"@id":"toot:featured","@type":"@id"},""" +
                """"showFeatured":"toot:showFeatured",""" +
                """"schema":"http://schema.org#",""" +
                """"PropertyValue":"schema:PropertyValue",""" +
                """"value":"schema:value"}]}"""
        assertEquals(expected, encoded)
    }
}
