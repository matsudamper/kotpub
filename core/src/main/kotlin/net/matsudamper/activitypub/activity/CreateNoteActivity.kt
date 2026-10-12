package net.matsudamper.activitypub.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.activitypub.activitypub.StringListSerializer
import net.matsudamper.activitypub.entity.ActivityPubId
import net.matsudamper.activitypub.note.Note

/**
 * [net.matsudamper.activitypub.note.Note] を包んで配る `Create`。
 *
 * `to` と `cc` は中の [net.matsudamper.activitypub.note.Note] と同じものを入れる。片方だけに入れると、
 * 実装によって配信先の判断が変わる。
 *
 * [OutgoingActivity] と分けているのは、あちらの `object` が
 * [net.matsudamper.activitypub.activitypub.LinkOrObject] で、受け取ったものを
 * そのまま返す `Accept` のための形だから。こちらは中身をこちらが組み立てる。
 */
@Serializable
data class CreateNoteActivity(
    /**
     * このアクティビティ自身の id。相手側の重複判定に使われる
     */
    @SerialName("id")
    val id: ActivityPubId,
    @SerialName("actor")
    val actor: String,
    @SerialName("published")
    val published: String,
    @SerialName("to")
    val to: List<String>,
    @SerialName("cc")
    val cc: List<String>,
    @SerialName("object")
    val target: Note,
) {
    @SerialName("@context")
    @Serializable(with = StringListSerializer::class)
    val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT

    /**
     * 相手はこの値を見て投稿の追加だと判断する。`Create` 以外は入らない
     */
    @SerialName("type")
    val type: String = "Create"
}
