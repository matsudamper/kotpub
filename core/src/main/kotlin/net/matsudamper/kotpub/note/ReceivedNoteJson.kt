package net.matsudamper.kotpub.note

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.matsudamper.kotpub.http.HttpUrl

/**
 * `Create` に埋まって届いた投稿と、ブーストされて取得しに行った投稿は同じ形なので、読み方を揃える。
 *
 * 投稿者と投稿の id が同じホストかどうかは確かめない。何と照らし合わせるかが受け取り方ごとに違う
 */
internal object ReceivedNoteJson {
    fun read(note: JsonObject): ReceivedNote? {
        if (note["type"].string() != "Note") return null
        val id = note["id"].string() ?: return null
        val attributedTo = note["attributedTo"].string() ?: return null
        val to = note["to"].strings()
        val cc = note["cc"].strings()
        return ReceivedNote(
            id = id,
            attributedTo = attributedTo,
            content = note["content"].string() ?: return null,
            published = note["published"].string()?.let { runCatching { Instant.parse(it) }.getOrNull() },
            to = to,
            cc = cc,
            inReplyTo = note["inReplyTo"].string(),
            summary = note["summary"].string(),
            sensitive = (note["sensitive"] as? JsonPrimitive)?.booleanOrNull ?: false,
            visibility = visibilityOf(actorId = attributedTo, to = to, cc = cc),
            attachments = note["attachment"].attachments(),
        )
    }

    /**
     * Mastodon と同じく、宛先の書き方から公開範囲を決める。`to` に公開があれば `cc` より優先する
     */
    fun visibilityOf(actorId: String, to: List<String>, cc: List<String>): NoteVisibility = when {
        PUBLIC in to -> NoteVisibility.Public
        PUBLIC in cc -> NoteVisibility.Unlisted
        (to + cc).any { it == "$actorId/followers" } -> NoteVisibility.Followers
        else -> NoteVisibility.Direct
    }

    fun JsonElement?.strings(): List<String> = when (this) {
        is JsonArray -> mapNotNull { it.string() }
        else -> listOfNotNull(string())
    }

    private fun JsonElement?.attachments(): List<ReceivedNoteAttachment> = when (this) {
        is JsonArray -> mapNotNull { it.attachment() }
        else -> listOfNotNull(attachment())
    }

    private fun JsonElement?.attachment(): ReceivedNoteAttachment? {
        val attachment = this as? JsonObject ?: return null
        val link = attachment["url"].link() ?: return null
        return ReceivedNoteAttachment(
            url = link.href,
            mediaType = attachment["mediaType"].string() ?: link.mediaType,
            name = attachment["name"].string(),
        )
    }

    // url は文字列のほか、Link オブジェクトやその配列で来ることがある。配列は先頭の http(s) の URL を使う
    private fun JsonElement?.link(): AttachmentLink? = when (this) {
        is JsonArray -> firstNotNullOfOrNull { it.link() }

        is JsonObject -> this["href"].string()
            ?.takeIf { it.isHttpUrl() }
            ?.let { AttachmentLink(href = it, mediaType = this["mediaType"].string()) }

        else -> string()?.takeIf { it.isHttpUrl() }?.let { AttachmentLink(href = it, mediaType = null) }
    }

    private fun String.isHttpUrl(): Boolean = HttpUrl.parse(this)?.scheme in setOf("http", "https")

    private class AttachmentLink(val href: String, val mediaType: String?)

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
}
