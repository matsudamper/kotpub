package net.matsudamper.activitypub.activitypub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Actor エンドポイントが返す JSON。
 *
 * Mastodon は WebFinger で見つけた URL をこの形で取得し、プロフィールカードと
 * 公開鍵を作る。`publicKey` が無い、あるいは `id` が取得 URL と食い違うと
 * アクターとして認識されない。
 *
 * `@context` のような記号入りのキーは Kotlin の識別子にできないので
 * [SerialName] で対応する。
 */
@Serializable
data class Actor(
    @SerialName("@context")
    @Serializable(with = ActorContextSerializer::class)
    val context: ActorContext = ActorContext,
    @SerialName("id")
    val id: String,
    /**
     * `Service` は「自動化されたアカウント」を表す。人間ではなく RSS の
     * 転送であることを相手に伝えられる。`Person` でも動く。
     */
    @SerialName("type")
    val type: String = TYPE_SERVICE,
    /** WebFinger の acct 名と一致させること。ずれると検索から辿り着けない */
    @SerialName("preferredUsername")
    val preferredUsername: String,
    @SerialName("name")
    val name: String,
    @SerialName("summary")
    val summary: String? = null,
    @SerialName("inbox")
    val inbox: String,
    @SerialName("endpoints")
    val endpoints: Endpoints,
    @SerialName("outbox")
    val outbox: String,
    /**
     * プロフィールに載せる投稿の一覧。Mastodon は未フォローでもここを引きに来る。
     * outbox はフォロー後のバックフィル向けで、プロフィール表示には使われない。
     */
    @SerialName("featured")
    val featured: String,
    @SerialName("followers")
    val followers: String,
    @SerialName("following")
    val following: String,
    /** プロフィールから開くリンク。Mastodon は無ければ id を使う */
    @SerialName("url")
    val url: String? = null,
    @SerialName("attachment")
    val attachment: List<ActorAttachment> = listOf(),
    /**
     * プロフィール画像。無ければ相手側の既定の画像が出る
     */
    @SerialName("icon")
    val icon: Image? = null,
    /**
     * プロフィールヘッダー。Mastodon は Actor の `image` をヘッダーとして使う
     */
    @SerialName("image")
    val image: Image? = null,
    @SerialName("publicKey")
    val publicKey: ActorPublicKey,
    /**
     * Mastodon 4.6 以降。ピン留め欄を出すか
     */
    @SerialName("showFeatured")
    val showFeatured: Boolean = false,
) {
    /**
     * プロフィール画像。
     *
     * Mastodon は URL を辿って自分のところに取り込み、以後はそちらを出す。
     * URL が変わらない限り取り直さないので、中身を差し替えても相手にはすぐ出ない。
     */
    @Serializable
    data class Image(
        @SerialName("type")
        val type: String = TYPE_IMAGE,
        @SerialName("url")
        val url: String,
    ) {
        companion object {
            const val TYPE_IMAGE: String = "Image"
        }
    }

    @Serializable
    data class Endpoints(
        @SerialName("sharedInbox")
        val sharedInbox: String,
    )

    companion object {
        const val TYPE_SERVICE: String = "Service"
    }
}

/**
 * プロフィールのリンク集の 1 項目。
 */
@Serializable
data class ActorAttachment(
    @SerialName("type")
    val type: String = TYPE_PROPERTY_VALUE,
    @SerialName("name")
    val name: String,
    @SerialName("value")
    val htmlContent: String,
) {
    companion object {
        const val TYPE_PROPERTY_VALUE: String = "PropertyValue"
    }
}

/**
 * Actor に埋め込む公開鍵。
 *
 * @param id 署名の `keyId` として飛んでくる値。`<actor>#main-key` の形にする
 * @param owner この鍵を持つアクターの `id`
 * @param publicKeyPem X.509 SubjectPublicKeyInfo の PEM（`BEGIN PUBLIC KEY`）
 */
@Serializable
data class ActorPublicKey(
    @SerialName("id")
    val id: String,
    @SerialName("owner")
    val owner: String,
    @SerialName("publicKeyPem")
    val publicKeyPem: String,
)
