package net.matsudamper.kotpub.note

import java.time.Instant
import java.util.IllformedLocaleException
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import net.matsudamper.kotpub.emoji.CustomEmojiTags
import net.matsudamper.kotpub.http.HttpUrl

/**
 * `Create` に埋まって届いた投稿と、ブーストされて取得しに行った投稿は同じ形なので、読み方を揃える。
 *
 * 投稿者と投稿の id が同じホストかどうかは確かめない。何と照らし合わせるかが受け取り方ごとに違う
 */
internal object ReceivedNoteJson {
    fun read(note: JsonObject): ReceivedNote? {
        val linkHref = when (note["type"].string()) {
            in NOTE_TYPES -> null
            LINK_TYPE -> note["href"].string()?.takeIf { it.isHttpUrl() } ?: return null
            else -> return null
        }
        val id = note["id"].string() ?: return null
        val attributedTo = note["attributedTo"].string() ?: return null
        val to = note["to"].strings()
        val cc = note["cc"].strings()
        val contentMap = note["contentMap"] as? JsonObject
        return ReceivedNote(
            id = id,
            attributedTo = attributedTo,
            content = note["content"].string() ?: contentMap?.values?.firstOrNull().string() ?: if (linkHref == null) return null else "",
            language = contentMap?.keys?.firstOrNull()?.let { languageOrNull(it) },
            published = note["published"].instant(),
            updated = note["updated"].instant(),
            to = to,
            cc = cc,
            inReplyTo = note["inReplyTo"].string(),
            quote = note.quote(),
            summary = note["summary"].string(),
            sensitive = (note["sensitive"] as? JsonPrimitive)?.booleanOrNull ?: false,
            visibility = visibilityOf(actorId = attributedTo, to = to, cc = cc),
            attachments = note["attachment"].attachments(),
            emojis = CustomEmojiTags.read(note["tag"]),
            mentions = note["tag"].mentions(),
            poll = note.poll(),
            linkHref = linkHref,
        )
    }

    /**
     * 本文が無く `name` と `inReplyTo` を持つ `Note` を、投票への票として読む。
     * `content` が無くても `contentMap` に本文があれば [read] が投稿として読むので、票にしない
     */
    fun readVote(note: JsonObject): ReceivedPollVote? {
        if (note["type"].string() != Note.NOTE_TYPE) return null
        if (note["content"].string() != null || note["contentMap"] is JsonObject) return null
        return ReceivedPollVote(
            id = note["id"].string() ?: return null,
            voterActorId = note["attributedTo"].string() ?: return null,
            questionId = note["inReplyTo"].idOrString() ?: return null,
            choice = note["name"].string() ?: return null,
        )
    }

    /**
     * `Link` は投稿者や宛先を持たない型なので、無いものは包んでいるアクティビティから補う。
     * `Note` はアクティビティを見ずに [read] と同じに読む。
     *
     * id は `Create` のときだけ補う。`Update` の id は作ったときの `Create` の id と違い、保存した投稿を指せない
     */
    fun read(note: JsonObject, activity: JsonObject): ReceivedNote? {
        if (note["type"].string() != LINK_TYPE) return read(note)
        val inherited = buildMap {
            ACTIVITY_KEYS_FOR_LINK.forEach { (activityKey, noteKey) ->
                activity[activityKey]?.let { put(noteKey, it) }
            }
            activity["actor"].idOrString()?.let { put("attributedTo", JsonPrimitive(it)) }
            if (activity["type"].string() == CREATE_TYPE) {
                activity["id"]?.let { put("id", it) }
            }
        }
        return read(JsonObject(inherited + note))
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

    /**
     * 投稿の言語を表す専用のプロパティは無く、Mastodon などは `contentMap` のキーで伝える。
     * 複数の言語が入っていても、Mastodon と同じく先頭だけを見る
     */
    private fun languageOrNull(contentMapKey: String): Locale? = try {
        Locale.Builder().setLanguageTag(contentMapKey).build().takeIf { it.toLanguageTag() != UNDETERMINED_LANGUAGE }
    } catch (_: IllformedLocaleException) {
        null
    }

    /**
     * 実装ごとに名前が違う。FEP-044f の `quote` を先に見て、無ければ古い名前を見る
     */
    private fun JsonObject.quote(): String? =
        QUOTE_KEYS.firstNotNullOfOrNull { key -> this[key].idOrString() }

    // Link オブジェクトで来ることもあるので、その場合は href を使う
    private fun JsonElement?.idOrString(): String? = when (this) {
        is JsonObject -> this["href"].string() ?: this["id"].string()
        is JsonArray -> firstNotNullOfOrNull { it.idOrString() }
        else -> string()
    }

    /**
     * 選択肢が無ければ投票として扱わない。`oneOf` と `anyOf` の両方があれば、Mastodon と同じく `anyOf` を使う
     */
    private fun JsonObject.poll(): ReceivedPoll? {
        val anyOf = this["anyOf"].pollOptions()
        val oneOf = this["oneOf"].pollOptions()
        val multipleChoice = anyOf.isNotEmpty()
        val options = if (multipleChoice) anyOf else oneOf
        if (options.isEmpty()) return null
        return ReceivedPoll(
            options = options,
            multipleChoice = multipleChoice,
            endsAt = this["endTime"].instant() ?: this["closed"].instant(),
            votersCount = this["votersCount"].int(),
        )
    }

    private fun JsonElement?.pollOptions(): List<ReceivedPollOption> {
        val options = this as? JsonArray ?: return listOf()
        return options.filterIsInstance<JsonObject>().mapNotNull { option ->
            ReceivedPollOption(
                name = option["name"].string() ?: return@mapNotNull null,
                votesCount = (option["replies"] as? JsonObject)?.get("totalItems").int(),
            )
        }
    }

    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun JsonElement?.mentions(): List<String> {
        val tags = when (this) {
            is JsonArray -> this
            else -> listOfNotNull(this)
        }
        return tags.filterIsInstance<JsonObject>()
            .filter { it["type"].string() == MENTION_TYPE }
            .mapNotNull { it["href"].string() }
            .distinct()
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

    private fun JsonElement?.instant(): Instant? = string()?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val QUOTE_KEYS = listOf("quote", "quoteUrl", "quoteUri", "_misskey_quote")

    private const val MENTION_TYPE = "Mention"

    private val NOTE_TYPES = setOf(Note.NOTE_TYPE, Note.QUESTION_TYPE)

    private const val LINK_TYPE = "Link"

    private const val CREATE_TYPE = "Create"

    private val ACTIVITY_KEYS_FOR_LINK = listOf(
        "to" to "to",
        "cc" to "cc",
        "published" to "published",
    )

    private const val UNDETERMINED_LANGUAGE = "und"

    private const val PUBLIC = "https://www.w3.org/ns/activitystreams#Public"
}
