package net.matsudamper.kotpub.activitypub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * サーバー自身のアクターの JSON。
 *
 * アカウントの [Actor] と違い、投稿やフォローの一覧を持たない。
 * 相手が署名を確かめるための `publicKey` と、アクターとして読ませるための最小限だけを返す
 */
@Serializable
data class InstanceActorDocument(
    @SerialName("@context")
    @Serializable(with = ActorContextSerializer::class)
    val context: ActorContext = ActorContext,
    @SerialName("id")
    val id: String,
    /** Mastodon の instance actor も `Application` を使う */
    @SerialName("type")
    val type: String = TYPE_APPLICATION,
    @SerialName("preferredUsername")
    val preferredUsername: String,
    @SerialName("name")
    val name: String,
    @SerialName("inbox")
    val inbox: String,
    @SerialName("endpoints")
    val endpoints: Actor.Endpoints,
    /** フォローされても受け付けないので、相手にも承認制だと見せる */
    @SerialName("manuallyApprovesFollowers")
    val manuallyApprovesFollowers: Boolean = true,
    @SerialName("publicKey")
    val publicKey: ActorPublicKey,
) {
    companion object {
        const val TYPE_APPLICATION: String = "Application"
    }
}
