package net.matsudamper.activitypub.note

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.activitypub.activity.CreateNoteActivity
import net.matsudamper.activitypub.entity.ActivityPubId

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
     * この投稿の id。相手はここをパーマリンクとして引きに来る
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
     * Mastodon がパーマリンクの別名として引くことがある
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
) {
    /**
     * 相手はこの値を見て投稿だと判断する。`Note` 以外は入らない
     */
    @SerialName("type")
    val type: String = "Note"
}
