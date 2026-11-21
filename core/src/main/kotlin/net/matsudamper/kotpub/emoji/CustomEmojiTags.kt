package net.matsudamper.kotpub.emoji

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `tag` に並ぶ `Emoji` からカスタム絵文字を読む。
 *
 * `tag` も `icon` も、配列 1 つのこともオブジェクト 1 つのこともある。
 * Activity Streams では単数と配列のどちらでも書けるため。
 */
internal object CustomEmojiTags {
    private const val EMOJI_TYPE = "Emoji"

    // 名前も画像 URL も仕様上の長さの上限が無く、受け取った文書の大きさまで入れられる。
    // 保存して公開画面にそのまま出すので、絵文字 1 つとして扱えない長さのものは返さない
    const val MAX_NAME_LENGTH: Int = 100
    const val MAX_IMAGE_URL_LENGTH: Int = 2048

    /**
     * 相手のサーバーが書いた URL をそのまま画面が読みに行くので、
     * http と https 以外の画像しか無い絵文字は返さない。同じ名前が並んだら先のものを使う
     */
    fun read(tag: JsonElement?): List<CustomEmoji> = elements(tag)
        .filterIsInstance<JsonObject>()
        .filter { it.string("type") == EMOJI_TYPE }
        .mapNotNull { emoji ->
            val name = emoji.string("name")?.takeIf { it.isNotBlank() && it.length <= MAX_NAME_LENGTH } ?: return@mapNotNull null
            val imageUrl = elements(emoji["icon"]).firstNotNullOfOrNull { iconUrl(it) } ?: return@mapNotNull null
            CustomEmoji(name = name, imageUrl = imageUrl)
        }
        .distinctBy { it.name }

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

        if (url.length > MAX_IMAGE_URL_LENGTH) return null
        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
