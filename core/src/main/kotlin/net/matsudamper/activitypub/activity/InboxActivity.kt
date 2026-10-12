package net.matsudamper.activitypub.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.activitypub.activitypub.LinkOrObject
import net.matsudamper.activitypub.activitypub.id

/**
 * inbox で受け取るアクティビティのうち、今のところ見ている部分だけ。
 *
 * `Follow` / `Undo` / `Delete` などの種類ごとの扱いは Phase 2 以降で足す。
 * ここでは「誰が」「何をしようとしているか」をログに出し、署名した相手と
 * `actor` が一致しているかを確かめるところまで。
 *
 * 未知のキーは [net.matsudamper.activitypub.json.AppJson] の設定で無視される。
 * ActivityPub のアクティビティは実装ごとに独自のプロパティが乗るため。
 */
@Serializable
data class InboxActivity(
    @SerialName("id")
    val id: String? = null,
    @SerialName("type")
    val type: String? = null,
    /** 実行した相手。URL 文字列のことも、アクターが丸ごと埋まっていることもある */
    @SerialName("actor")
    val actor: LinkOrObject? = null,
    /** `object` は Kotlin の予約語なので名前を変えて受ける */
    @SerialName("object")
    val target: LinkOrObject? = null,
    @SerialName("content")
    val content: String? = null,
) {
    /** 実行した相手のアクター id。取れなければ null */
    val actorId: String?
        get() = actor?.id
}
