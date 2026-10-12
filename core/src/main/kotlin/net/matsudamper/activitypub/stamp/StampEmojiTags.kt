package net.matsudamper.activitypub.stamp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * カスタム絵文字の画像を、アクティビティの `tag` から引く。
 *
 * カスタム絵文字は `content` に `:name:` としか入らないので、画像は同じ
 * アクティビティの `tag` に並ぶ `Emoji` から取る。
 *
 * `tag` も `icon` も、配列 1 つのこともオブジェクト 1 つのこともある。
 * Activity Streams では単数と配列のどちらでも書けるため。
 */
internal object StampEmojiTags {
    private const val EMOJI_TYPE = "Emoji"

    /**
     * [emoji] と同じ名前の `Emoji` の画像 URL。無ければ null。
     *
     * 相手のサーバーが書いた URL をそのまま画面が読みに行くので、
     * http と https 以外は返さない
     */
    fun imageUrl(
        rawActivityJson: JsonObject,
        emoji: String,
    ): String? {
        return elements(rawActivityJson["tag"])
            .filterIsInstance<JsonObject>()
            .filter { it.string("type") == EMOJI_TYPE && it.string("name") == emoji }
            .firstNotNullOfOrNull { tag -> elements(tag["icon"]).firstNotNullOfOrNull { iconUrl(it) } }
    }

    private fun elements(element: JsonElement?): List<JsonElement> = when (element) {
        is JsonArray -> element.toList()
        null -> listOf()
        else -> listOf(element)
    }

    private fun iconUrl(icon: JsonElement): String? {
        val url = when (icon) {
            is JsonObject -> icon.string("url")
            is JsonPrimitive -> icon.takeIf { it.isString }?.content
            else -> null
        } ?: return null

        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
