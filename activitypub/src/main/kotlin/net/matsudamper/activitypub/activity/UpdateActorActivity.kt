package net.matsudamper.activitypub.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.activitypub.activitypub.Actor
import net.matsudamper.activitypub.activitypub.StringListSerializer
import net.matsudamper.activitypub.entity.ActivityPubId

/**
 * アクター情報の変更を伝える `Update`。
 *
 * server-to-server の `Update` は差分ではなく `object` 全体で置き換える前提なので、
 * Actor エンドポイントと同じ完全な Actor 文書を載せる。
 */
@Serializable
data class UpdateActorActivity(
    @SerialName("id")
    val id: ActivityPubId,
    @SerialName("actor")
    val actor: String,
    @SerialName("to")
    val to: List<String>,
    @SerialName("object")
    val updatedActor: Actor,
) {
    @SerialName("@context")
    @Serializable(with = StringListSerializer::class)
    val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT

    @SerialName("type")
    val type: String = "Update"
}
