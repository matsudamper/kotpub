package net.matsudamper.kotpub.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.kotpub.activitypub.StringListSerializer
import net.matsudamper.kotpub.entity.ActivityPubId
import net.matsudamper.kotpub.note.Note

/**
 * 投稿の書き換えを伝える `Update`。
 *
 * server-to-server の `Update` は差分ではなく `object` 全体で置き換える前提なので、
 * [CreateNoteActivity] と同じ完全な [Note] を載せる。`to` と `cc` も中の [Note] と同じものを入れる
 */
@Serializable
data class UpdateNoteActivity(
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

    @SerialName("type")
    val type: String = "Update"
}
