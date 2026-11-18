package net.matsudamper.kotpub.note

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.activity.ActivityStreamsIri
import net.matsudamper.kotpub.activity.CreateNoteActivity
import net.matsudamper.kotpub.emoji.EmojiObject
import net.matsudamper.kotpub.entity.ActivityPubId

/**
 * 配信する投稿。Mastodon のタイムラインに 1 件として並ぶもの。
 *
 * `to` に公開を表す URI を入れると、フォロワーのホームタイムラインに出るうえ、
 * 相手のインスタンスの公開タイムラインからも見える。`cc` にフォロワーの
 * コレクションを入れるのは Mastodon の慣習で、これが無いと配信されても
 * ホームタイムラインに並ばないことがある。
 *
 * `@context` は単体で返すときだけ入れる。[CreateNoteActivity] に包んで送るときは
 * 外側が持っているので、中で重ねると同じものを 2 回書くことになる。
 */
@Serializable
data class Note(
    @SerialName("@context")
    val context: List<String>? = null,
    /**
     * この投稿の id。相手はここをパーマリンクとして取得しに来る
     */
    @SerialName("id")
    val id: ActivityPubId,
    /**
     * 投稿したアクターの id
     */
    @SerialName("attributedTo")
    val attributedTo: String,
    /**
     * 本文の HTML。許可されていないタグは相手側で落とされる
     */
    @SerialName("content")
    val content: String,
    /**
     * ISO 8601。相手のタイムラインでの並び順になる
     */
    @SerialName("published")
    val published: String,
    /**
     * ISO 8601。最後に書き換えた日時。書き換えていなければ null。Mastodon は新しくなっていない `Update` を捨てる
     */
    @SerialName("updated")
    val updated: String? = null,
    @SerialName("to")
    val to: List<String>,
    @SerialName("cc")
    val cc: List<String>,
    /**
     * Mastodon がパーマリンクの別名として取得しに来ることがある
     */
    @SerialName("atomUri")
    val atomUri: String? = null,
    /**
     * 閲覧注意の注意書き。Mastodon は本文を畳んでこれだけを見せる。閲覧注意でなければ null
     */
    @SerialName("summary")
    val summary: String? = null,
    /**
     * Mastodon は [summary] があっても、これが false だと画像をぼかさない
     */
    @SerialName("sensitive")
    val sensitive: Boolean = false,
    /**
     * プロフィールや検索から開くリンク。無ければ [id] が使われる
     */
    @SerialName("url")
    val url: String? = null,
    @SerialName("attachment")
    val attachment: List<NoteDocument>,
    /**
     * 返信先の投稿の id。返信でなければ null
     */
    @SerialName("inReplyTo")
    val inReplyTo: String?,
    /**
     * 引用した投稿の id。引用でなければ null。FEP-044f の名前で、Mastodon 4.4 以降が読む
     */
    @SerialName("quote")
    val quote: String?,
    /**
     * [quote] と同じ値。FEP-044f より前の Mastodon のフォークや Pleroma 系はこちらを読む
     */
    @SerialName("quoteUrl")
    val quoteUrl: String?,
    /**
     * [quote] と同じ値。Misskey 系はこちらを読む
     */
    @SerialName("_misskey_quote")
    val misskeyQuote: String?,
    /**
     * Mastodon はここの `Mention` を見て、宛てられたアカウントに通知する。本文のリンクだけでは通知しない。
     * 本文の `:name:` は、ここの `Emoji` に同じ名前があるものだけ画像に置き換えられる
     */
    @SerialName("tag")
    val tag: List<NoteTag>,
    /**
     * 誰がこの投稿を引用できるか。FEP-044f の名前で、Mastodon 4.5 以降はこれが無いと引用させない
     */
    @SerialName("interactionPolicy")
    val interactionPolicy: InteractionPolicy,
    /**
     * 1 つだけ選ばせる投票の選択肢。投票でないか、複数選べる投票なら null
     */
    @SerialName("oneOf")
    val oneOf: List<QuestionOption>? = null,
    /**
     * 複数選べる投票の選択肢。投票でないか、1 つだけ選ばせる投票なら null
     */
    @SerialName("anyOf")
    val anyOf: List<QuestionOption>? = null,
    /**
     * ISO 8601。投票の締め切り。投票でなければ null
     */
    @SerialName("endTime")
    val endTime: String? = null,
    /**
     * 投票した人数。投票でなければ null
     */
    @SerialName("votersCount")
    val votersCount: Int? = null,
) {
    /**
     * 相手はこの値を見て投稿だと判断する。投票付きの投稿は Mastodon に合わせて `Question` にする
     */
    @SerialName("type")
    val type: String = if (oneOf != null || anyOf != null) QUESTION_TYPE else NOTE_TYPE

    companion object {
        internal const val NOTE_TYPE = "Note"
        internal const val QUESTION_TYPE = "Question"
    }
}

/**
 * 投稿への操作を誰に許すか。GoToSocial が決めた形を FEP-044f が引用に使っている
 */
@Serializable
data class InteractionPolicy(
    @SerialName("canQuote")
    val canQuote: InteractionRule,
) {
    companion object {
        /**
         * 誰でも承認を待たずに引用できる。こちらの投稿は全て公開なので、引用も誰にでも許す。
         * 引用した側から `QuoteRequest` が届き、`Accept` を返して初めて相手の画面で引用が成立する
         */
        val QUOTABLE_BY_ANYONE: InteractionPolicy = InteractionPolicy(
            canQuote = InteractionRule(automaticApproval = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE)),
        )
    }
}

/**
 * @param automaticApproval 承認を待たずに許す相手。アクターの id、フォロワーのコレクション、公開を表す URI のどれか
 */
@Serializable
data class InteractionRule(
    @SerialName("automaticApproval")
    val automaticApproval: List<String>,
)

/**
 * 投稿に付けた画像。Mastodon は `Document` の `mediaType` が画像なら画像として並べる
 */
@Serializable
data class NoteDocument(
    @SerialName("mediaType")
    val mediaType: String,
    @SerialName("url")
    val url: String,
    /**
     * 画像の説明文。Mastodon は代替テキストとして出す
     */
    @SerialName("name")
    val name: String? = null,
) {
    @SerialName("type")
    val type: String = "Document"
}

@Serializable(with = NoteTagSerializer::class)
sealed interface NoteTag {
    val type: String
}

/**
 * 実際の型のシリアライザを型から引くとリフレクションが要り、GraalVM のネイティブイメージでは見つからない。
 * 型ごとのシリアライザをここで直接選ぶ
 */
internal object NoteTagSerializer : KSerializer<NoteTag> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("net.matsudamper.kotpub.note.NoteTag")

    override fun serialize(encoder: Encoder, value: NoteTag) {
        when (value) {
            is NoteMentionTag -> encoder.encodeSerializableValue(NoteMentionTag.serializer(), value)
            is NoteEmojiTag -> encoder.encodeSerializableValue(NoteEmojiTag.serializer(), value)
        }
    }

    override fun deserialize(decoder: Decoder): NoteTag {
        val jsonDecoder = decoder as? JsonDecoder ?: throw SerializationException("NoteTag は JSON からだけ読める")
        val element = jsonDecoder.decodeJsonElement()
        val type = ((element as? JsonObject)?.get("type") as? JsonPrimitive)?.content
        val serializer = if (type == EmojiObject.TYPE_EMOJI) NoteEmojiTag.serializer() else NoteMentionTag.serializer()
        return jsonDecoder.json.decodeFromJsonElement(serializer, element)
    }
}

/**
 * 投稿で宛てたアカウント
 *
 * @param href 宛てたアクターの id
 * @param name `@user@example.com` の形
 */
@Serializable
data class NoteMentionTag(
    @SerialName("href")
    val href: String,
    @SerialName("name")
    val name: String,
) : NoteTag {
    @SerialName("type")
    override val type: String = "Mention"
}

/**
 * 本文に入れた、こちらのサーバーのカスタム絵文字。[EmojiObject] と同じ形で載せる
 *
 * @param name `:name:` の形
 */
@Serializable
data class NoteEmojiTag(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("icon")
    val icon: EmojiObject.Icon,
) : NoteTag {
    @SerialName("type")
    override val type: String = EmojiObject.TYPE_EMOJI
}

internal fun List<NoteAttachment>.toNoteDocuments(): List<NoteDocument> =
    map { attachment ->
        NoteDocument(mediaType = attachment.mediaType, url = attachment.url, name = attachment.description)
    }

internal fun StoredNote.toNoteTags(): List<NoteTag> =
    mentions.map { mention -> NoteMentionTag(href = mention.actorId, name = "@${mention.acct}") } +
        emojis.map { emoji -> NoteEmojiTag(id = emoji.id, name = emoji.name, icon = emoji.icon) }

/**
 * 宛てたアクターを `cc` に入れないと、Mastodon は通知もせず、宛てた相手の画面にも出さない
 */
internal fun StoredNote.ccWith(followers: String): List<String> =
    listOf(followers) + mentions.map { it.actorId }
