package net.matsudamper.activitypub.activitypub

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Actor の `@context`。ActivityStreams と security に加え、Mastodon 拡張の語彙を載せる。
 */
object ActorContext

object ActorContextSerializer : KSerializer<ActorContext> {
    private const val ACTIVITY_STREAMS = "https://www.w3.org/ns/activitystreams"
    private const val SECURITY = "https://w3id.org/security/v1"
    private const val TOOT_NAMESPACE = "http://joinmastodon.org/ns#"
    private const val SCHEMA_NAMESPACE = "http://schema.org#"

    override val descriptor: SerialDescriptor =
        SerialDescriptor("net.matsudamper.activitypub.activitypub.ActorContext", JsonPrimitive.serializer().descriptor)

    override fun deserialize(decoder: Decoder): ActorContext {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("ActorContextSerializer は JSON でのみ使える")
        jsonDecoder.decodeJsonElement()
        return ActorContext
    }

    override fun serialize(
        encoder: Encoder,
        value: ActorContext,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("ActorContextSerializer は JSON でのみ使える")

        jsonEncoder.encodeJsonElement(
            buildJsonArray {
                add(JsonPrimitive(ACTIVITY_STREAMS))
                add(JsonPrimitive(SECURITY))
                add(
                    buildJsonObject {
                        put("toot", JsonPrimitive(TOOT_NAMESPACE))
                        put(
                            "featured",
                            buildJsonObject {
                                put("@id", JsonPrimitive("toot:featured"))
                                put("@type", JsonPrimitive("@id"))
                            },
                        )
                        put("showFeatured", JsonPrimitive("toot:showFeatured"))
                        // attachment の PropertyValue は ActivityStreams の語彙に無い
                        put("schema", JsonPrimitive(SCHEMA_NAMESPACE))
                        put("PropertyValue", JsonPrimitive("schema:PropertyValue"))
                        put("value", JsonPrimitive("schema:value"))
                    },
                )
            },
        )
    }
}
