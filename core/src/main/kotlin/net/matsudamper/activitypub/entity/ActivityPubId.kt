package net.matsudamper.activitypub.entity

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * ActivityPub のオブジェクトとアクティビティの id。
 *
 * 仕様で、誰でも取りに行ける公開の URI と決まっている
 * （[ActivityPub 3.1 Object Identifiers](https://www.w3.org/TR/activitypub/#obj-id)）。
 * 相手はこの値を鍵にして受け取り済みのものを引き当てるので、1 文字でも違うと別のものになる。
 *
 * ただの URL と混ぜないために型を分ける。JSON には中の文字列がそのまま出る。
 */
@Serializable
@JvmInline
value class ActivityPubId(
    val value: String,
)
