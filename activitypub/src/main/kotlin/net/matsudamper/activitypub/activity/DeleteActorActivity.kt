package net.matsudamper.activitypub.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.activitypub.activitypub.StringListSerializer
import net.matsudamper.activitypub.entity.ActivityPubId

/**
 * アクターを消したことを伝える `Delete`。
 *
 * これを配らない限り、相手のサーバーにはアクターとフォローの関係が残る。
 * 残ったまま同じ名前でアカウントを作り直すと、相手はこちらを以前のアクターの
 * ままだと思っていて、フォローしていない相手に投稿が届いたように見える。
 *
 * `object` はアクター自身の id。相手はこれが送り主と同じかどうかで、
 * 投稿の削除ではなくアクターの削除だと判断する（こちらの
 * [net.matsudamper.activitypub.inbox.DeleteActorHandler] も同じ判定をしている）。
 *
 * 宛先は公開だけにする。アクターの削除はフォロワー以外にも伝わってよく、
 * Mastodon もこの形で送ってくる。
 */
@Serializable
data class DeleteActorActivity(
    /**
     * このアクティビティ自身の id。消すアクターの id と同じにすると、
     * 相手の重複判定でアクター文書と衝突する
     */
    @SerialName("id")
    val id: ActivityPubId,
    @SerialName("actor")
    val actor: String,
    @SerialName("to")
    val to: List<String>,
    @SerialName("object")
    val target: String,
) {
    @SerialName("@context")
    @Serializable(with = StringListSerializer::class)
    val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT

    /**
     * 相手はこの値を見て削除だと判断する。`Delete` 以外は入らない
     */
    @SerialName("type")
    val type: String = "Delete"
}
