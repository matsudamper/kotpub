package net.matsudamper.activitypub.activitypub

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「文字列 1 個」と「文字列の配列」のどちらでも来るフィールドを、常に [List] として扱う serializer。
 *
 * ActivityPub では `@context` `to` `cc` `type` などが両方の形で流れてくる。
 * 受信側では単一文字列を 1 要素のリストに正規化し、送信側では逆に
 * 1 要素なら文字列、それ以外は配列として出力する。
 * 常に配列で出力すると `"type": ["Note"]` のような形になり、
 * 単一文字列を前提にしている実装が解釈できないことがあるため。
 */
object StringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("net.matsudamper.activitypub.activitypub.StringList", delegate.descriptor)

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("StringListSerializer は JSON でのみ使える")

        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> {
                element.map { item ->
                    val primitive =
                        item as? JsonPrimitive
                            ?: throw SerializationException("配列の要素は文字列である必要がある: $item")
                    if (!primitive.isString) {
                        throw SerializationException("配列の要素は文字列である必要がある: $item")
                    }
                    primitive.content
                }
            }

            // JsonNull も JsonPrimitive なので、文字列判定より先に弾く
            JsonNull -> {
                emptyList()
            }

            is JsonPrimitive -> {
                if (!element.isString) {
                    throw SerializationException("文字列か文字列の配列である必要がある: $element")
                }
                listOf(element.content)
            }

            else -> {
                throw SerializationException("文字列か文字列の配列である必要がある: $element")
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: List<String>,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("StringListSerializer は JSON でのみ使える")

        if (value.size == 1) {
            jsonEncoder.encodeJsonElement(JsonPrimitive(value.single()))
        } else {
            jsonEncoder.encodeJsonElement(JsonArray(value.map { JsonPrimitive(it) }))
        }
    }
}
