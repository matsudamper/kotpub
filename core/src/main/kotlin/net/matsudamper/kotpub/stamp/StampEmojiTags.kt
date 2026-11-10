package net.matsudamper.kotpub.stamp

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.emoji.CustomEmojiTags

/**
 * カスタム絵文字の画像を、アクティビティの `tag` から探す。
 *
 * カスタム絵文字は `content` に `:name:` としか入らないので、画像は同じ
 * アクティビティの `tag` に並ぶ `Emoji` から取る。
 */
internal object StampEmojiTags {
    /**
     * [emoji] と同じ名前の `Emoji` の画像 URL。無ければ null。
     */
    fun imageUrl(
        rawActivityJson: JsonObject,
        emoji: String,
    ): String? = CustomEmojiTags.read(rawActivityJson["tag"]).firstOrNull { it.name == emoji }?.imageUrl
}
