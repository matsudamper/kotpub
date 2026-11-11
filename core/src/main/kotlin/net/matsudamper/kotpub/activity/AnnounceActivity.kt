package net.matsudamper.kotpub.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.kotpub.activitypub.StringListSerializer

/**
 * こちらのアクターが他の投稿をブーストする `Announce`。[BoostActivities] で組み立てる。
 *
 * [OutgoingActivity] と分けているのは、宛先の `to` / `cc` が要るから。宛先に公開を入れないと、
 * 受け取る側でブーストとしてタイムラインに載らない。
 */
@Serializable
data class AnnounceActivity(
    @SerialName("id")
    val id: String,
    @SerialName("actor")
    val actor: String,
    @SerialName("published")
    val published: String,
    @SerialName("to")
    val to: List<String>,
    @SerialName("cc")
    val cc: List<String>,
    /** ブーストする投稿の id */
    @SerialName("object")
    val target: String,
) {
    @SerialName("@context")
    @Serializable(with = StringListSerializer::class)
    val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT

    @SerialName("type")
    val type: String = "Announce"
}
