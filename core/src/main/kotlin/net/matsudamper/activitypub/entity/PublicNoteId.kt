package net.matsudamper.activitypub.entity

import kotlin.jvm.JvmInline

/**
 * 投稿を外から指す識別子。URL のパスに入り、[ActivityPubId] の組み立てにも使う。
 *
 * この module は投稿の置き先を口でしか知らないので、置き先が何を主キーにしているかは
 * ここには出さない。
 */
@JvmInline
value class PublicNoteId(
    val value: String,
)
