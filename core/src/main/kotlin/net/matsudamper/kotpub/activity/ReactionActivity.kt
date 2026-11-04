package net.matsudamper.kotpub.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.kotpub.activitypub.StringListSerializer

/**
 * こちらから相手の投稿に送るお気に入り・スタンプの `Like`。作るのは [ReactionActivities]。
 *
 * スタンプも `EmojiReact` ではなく `content` 付きの `Like` で送る。Mastodon は
 * `EmojiReact` を受け取らないが、`Like` ならお気に入りとして数える。Misskey と
 * Pleroma 系は `content` の絵文字をリアクションとして扱う。
 *
 * 配送先は対象の投稿の作者の inbox。
 */
@Serializable
data class ReactionActivity(
    /** このアクティビティ自身の id。取り消しの `Undo` で相手がこの id を突き合わせる */
    @SerialName("id")
    val id: String,
    @SerialName("actor")
    val actor: String,
    /** 対象の投稿の id */
    @SerialName("object")
    val target: String,
    /** お気に入りでは null */
    @SerialName("content")
    val content: String?,
    /** 古い Misskey は `content` ではなくこちらを読む */
    @SerialName("_misskey_reaction")
    val misskeyReaction: String?,
    /** カスタム絵文字の画像。相手は `content` の `:name:` と同じ名前の `Emoji` を探す */
    @SerialName("tag")
    val tag: List<EmojiTag>,
) {
    @SerialName("@context")
    @Serializable(with = StringListSerializer::class)
    val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT

    @SerialName("type")
    val type: String = TYPE

    @Serializable
    data class EmojiTag(
        @SerialName("id")
        val id: String,
        /** `:name:` の形。`content` と同じ文字列でないと相手が画像を見つけられない */
        @SerialName("name")
        val name: String,
        @SerialName("icon")
        val icon: Icon,
    ) {
        @SerialName("type")
        val type: String = "Emoji"
    }

    @Serializable
    data class Icon(
        @SerialName("url")
        val url: String,
        @SerialName("mediaType")
        val mediaType: String?,
    ) {
        @SerialName("type")
        val type: String = "Image"
    }

    companion object {
        const val TYPE: String = "Like"
    }
}
