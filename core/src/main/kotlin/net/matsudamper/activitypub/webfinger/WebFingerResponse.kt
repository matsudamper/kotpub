package net.matsudamper.activitypub.webfinger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WebFinger (RFC 7033) の JRD レスポンス。
 *
 * Mastodon は `@admin@example.com` の検索でまずここを引き、`links` の
 * `rel: "self"` から Actor の URL を得る。ActivityPub 自体の仕様ではないが、
 * この 1 ホップ目が無いとアカウントを発見できない。
 *
 * @param subject 問い合わせられた `acct:` をそのまま返す
 * @param aliases 同じ対象を指す別の URI。Actor の id を入れておく
 */
@Serializable
data class WebFingerResponse(
    @SerialName("subject")
    val subject: String,
    @SerialName("aliases")
    val aliases: List<String>? = null,
    @SerialName("links")
    val links: List<WebFingerLink>,
)

/**
 * @param rel リンクの種類。Actor を指すものは `self`
 * @param type 参照先の Content-Type。`self` では `application/activity+json`
 */
@Serializable
data class WebFingerLink(
    @SerialName("rel")
    val rel: String,
    @SerialName("type")
    val type: String? = null,
    @SerialName("href")
    val href: String? = null,
) {
    companion object {
        const val REL_SELF: String = "self"
    }
}
