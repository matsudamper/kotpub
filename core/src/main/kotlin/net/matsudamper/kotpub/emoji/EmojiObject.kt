package net.matsudamper.kotpub.emoji

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.kotpub.activity.ActivityStreamsIri
import net.matsudamper.kotpub.activitypub.StringListSerializer

/**
 * こちらのサーバーのカスタム絵文字を、アクティビティの `tag` や絵文字の id の URL で見せる形。
 *
 * 相手は [id] で同じ絵文字を見分けて取り込む。画像を差し替えたら [id] か [icon] の URL を変えないと、
 * 取り込み済みの相手には古い画像が残る
 *
 * @param name `:name:` の形
 */
@Serializable
data class EmojiObject(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("icon")
    val icon: Icon,
    @SerialName("type")
    val type: String = TYPE_EMOJI,
) {
    @Serializable
    data class Icon(
        @SerialName("url")
        val url: String,
        @SerialName("mediaType")
        val mediaType: String,
        @SerialName("type")
        val type: String = TYPE_IMAGE,
    )

    /**
     * 絵文字の id の URL へ単体で返すときの形。`tag` に載せるときは `@context` を付けない
     */
    @Serializable
    data class Document(
        @SerialName("@context")
        @Serializable(with = StringListSerializer::class)
        val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT,
        @SerialName("id")
        val id: String,
        @SerialName("name")
        val name: String,
        @SerialName("icon")
        val icon: Icon,
        @SerialName("type")
        val type: String = TYPE_EMOJI,
    )

    fun toDocument(): Document = Document(id = id, name = name, icon = icon)

    companion object {
        const val TYPE_EMOJI: String = "Emoji"
        const val TYPE_IMAGE: String = "Image"
    }
}
