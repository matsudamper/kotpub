package net.matsudamper.kotpub.quote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.matsudamper.kotpub.activity.ActivityStreamsIri

/**
 * 引用を認めたことを表す文書。FEP-044f の形。
 *
 * Mastodon は `Accept` の `result` に入っていた id を取得して、[attributedTo] が引用された投稿の持ち主か、
 * [interactingObject] と [interactionTarget] が引用の組と合っているかを確かめてから引用を成立させる
 *
 * @param attributedTo 認めたアクター。引用された投稿の持ち主
 * @param interactingObject 引用した投稿の id
 * @param interactionTarget 引用された投稿の id
 */
@Serializable
data class QuoteAuthorization(
    @SerialName("@context")
    val context: JsonElement = CONTEXT,
    @SerialName("id")
    val id: String,
    @SerialName("attributedTo")
    val attributedTo: String,
    @SerialName("interactingObject")
    val interactingObject: String,
    @SerialName("interactionTarget")
    val interactionTarget: String,
) {
    @SerialName("type")
    val type: String = TYPE

    companion object {
        const val TYPE: String = "QuoteAuthorization"

        private val CONTEXT: JsonElement = JsonArray(
            ActivityStreamsIri.DEFAULT_CONTEXT.map { JsonPrimitive(it) } +
                JsonObject(
                    mapOf(
                        "QuoteAuthorization" to JsonPrimitive("https://w3id.org/fep/044f#QuoteAuthorization"),
                        "gts" to JsonPrimitive("https://gotosocial.org/ns#"),
                        "interactingObject" to idTerm("gts:interactingObject"),
                        "interactionTarget" to idTerm("gts:interactionTarget"),
                    ),
                ),
        )

        private fun idTerm(id: String): JsonObject =
            JsonObject(mapOf("@id" to JsonPrimitive(id), "@type" to JsonPrimitive("@id")))
    }
}
