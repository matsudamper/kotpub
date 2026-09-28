package net.matsudamper.activitypub.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.activitypub.activitypub.StringListSerializer
import net.matsudamper.activitypub.entity.ActivityPubId

/**
 * 投稿を消したことを伝える `Delete`。
 *
 * こちらで消しても相手のサーバーには残る。これを配らない限り、
 * フォロワーのタイムラインからは消えない。
 *
 * `to` と `cc` は元の投稿と同じものを入れる。宛先を見て配るかどうかを決める実装があり、
 * 元の投稿と揃っていないと消したことが無視されてタイムラインに残る。
 */
@Serializable
data class DeleteNoteActivity(
    /**
     * このアクティビティ自身の id。消す投稿の id ではない。
     *
     * 相手の重複判定に使われるので、消した投稿の id と同じにしてはいけない
     */
    @SerialName("id")
    val id: ActivityPubId,
    @SerialName("actor")
    val actor: String,
    @SerialName("to")
    val to: List<String>,
    @SerialName("cc")
    val cc: List<String>,
    @SerialName("object")
    val target: Tombstone,
) {
    @SerialName("@context")
    @Serializable(with = StringListSerializer::class)
    val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT

    /**
     * 相手はこの値を見て削除だと判断する。`Delete` 以外は入らない
     */
    @SerialName("type")
    val type: String = "Delete"

    /**
     * 消した投稿を表す `Tombstone`（墓標）。[DeleteNoteActivity] の `object` に入れて相手の inbox へ送る。
     *
     * 送り出すためだけの形で、保存もしないし受け取ったものをこれに読むこともしない。
     * 消えたことだけを表すので本文は持たない。id だけを送る実装もあるが、
     * 型を付けておくと相手側が何を消せばよいか一意に決まる。
     *
     * 名前は ActivityStreams 2.0 の語彙で、`type` に出る値もこれ。別の語にすると相手が削除と分からない。
     */
    @Serializable
    data class Tombstone(
        /**
         * 消す対象の id
         */
        @SerialName("id")
        val id: ActivityPubId,
    ) {
        /**
         * 相手はこの値を見て、消えたオブジェクトだと判断する。`Tombstone` 以外は入らない
         */
        @SerialName("type")
        val type: String = "Tombstone"
    }
}
