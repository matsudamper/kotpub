package net.matsudamper.kotpub.note

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 投稿に添えた画像 1 枚。
 *
 * @param url 相手はここから取得して写し取る。期限で切れる URL にすると、後から取りに来た相手が取れない
 * @param mediaType `image/png` など
 * @param description 代替テキスト
 * @param width 分からなければ null
 * @param height 分からなければ null
 */
data class StoredNoteAttachment(
    val url: String,
    val mediaType: String,
    val description: String?,
    val width: Int?,
    val height: Int?,
)

/**
 * [Note] の `attachment` に入る 1 件。Mastodon は `name` を代替テキストとして表示する
 */
@Serializable
data class NoteDocument(
    @SerialName("mediaType")
    val mediaType: String,
    @SerialName("url")
    val url: String,
    @SerialName("name")
    val name: String? = null,
    @SerialName("width")
    val width: Int? = null,
    @SerialName("height")
    val height: Int? = null,
) {
    /**
     * Mastodon も画像を `Document` として配る
     */
    @SerialName("type")
    val type: String = "Document"
}

internal fun StoredNoteAttachment.toDocument(): NoteDocument =
    NoteDocument(
        mediaType = mediaType,
        url = url,
        name = description,
        width = width,
        height = height,
    )
