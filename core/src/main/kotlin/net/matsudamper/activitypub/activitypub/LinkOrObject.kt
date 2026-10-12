package net.matsudamper.activitypub.activitypub

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * アクティビティの `object` のように、URL 文字列とオブジェクトの両方を取りうるフィールド。
 *
 * `Undo` や `Accept` の `object` は、相手の実装によって
 * 対象アクティビティの id だけが文字列で入っていたり、アクティビティ全体が
 * 埋め込まれていたりする。どちらで来ても受け取れるようにする。
 */
@Serializable(with = LinkOrObjectSerializer::class)
sealed interface LinkOrObject {
    /** id (URL) だけが入っていた場合 */
    data class Link(
        val href: String,
    ) : LinkOrObject

    /**
     * オブジェクトが丸ごと埋め込まれていた場合。
     * 中身の型はアクティビティごとに違うので、ここでは解釈せず [JsonObject] のまま持つ。
     */
    data class Embedded(
        val json: JsonObject,
    ) : LinkOrObject
}

/**
 * 指している対象の id。
 *
 * 埋め込まれたオブジェクトの場合は中の `id` を読む。id を持たないオブジェクトも
 * 書けてしまうので、取れなければ null。
 */
val LinkOrObject.id: String?
    get() =
        when (this) {
            is LinkOrObject.Link -> href
            is LinkOrObject.Embedded -> (json["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

/** [LinkOrObject] の JSON 表現を、文字列かオブジェクトかで振り分ける serializer */
object LinkOrObjectSerializer : KSerializer<LinkOrObject> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("net.matsudamper.activitypub.activitypub.LinkOrObject", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LinkOrObject {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("LinkOrObjectSerializer は JSON でのみ使える")

        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonObject -> {
                LinkOrObject.Embedded(element)
            }

            is JsonPrimitive -> {
                if (!element.isString) {
                    throw SerializationException("URL 文字列かオブジェクトである必要がある: $element")
                }
                LinkOrObject.Link(element.content)
            }

            else -> {
                throw SerializationException("URL 文字列かオブジェクトである必要がある: $element")
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: LinkOrObject,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("LinkOrObjectSerializer は JSON でのみ使える")

        when (value) {
            is LinkOrObject.Link -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.href))
            is LinkOrObject.Embedded -> jsonEncoder.encodeJsonElement(value.json)
        }
    }
}
