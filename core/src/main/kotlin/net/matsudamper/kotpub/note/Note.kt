package net.matsudamper.kotpub.note

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.kotpub.activity.CreateNoteActivity
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
    @SerialName("to")
    val to: List<String>,
    @SerialName("cc")
    val cc: List<String>,
    /**
     * Mastodon がパーマリンクの別名として取得しに来ることがある
     */
    @SerialName("atomUri")
    val atomUri: String? = null,
    @SerialName("sensitive")
    val sensitive: Boolean = false,
    /**
     * プロフィールや検索から開くリンク。無ければ [id] が使われる
     */
    @SerialName("url")
    val url: String? = null,
    @SerialName("attachment")
    val attachment: List<NoteDocument>,
) {
    /**
     * 相手はこの値を見て投稿だと判断する。`Note` 以外は入らない
     */
    @SerialName("type")
    val type: String = "Note"
}

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

internal fun List<NoteAttachment>.toNoteDocuments(): List<NoteDocument> =
    map { attachment ->
        NoteDocument(mediaType = attachment.mediaType, url = attachment.url, name = attachment.description)
    }
