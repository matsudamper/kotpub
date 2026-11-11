package net.matsudamper.kotpub.activity

import java.time.Instant
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.json.AppJson

/**
 * こちらのアクターが付けるブーストと、その取り消し
 */
object BoostActivities {
    /**
     * 公開でブーストする。投稿者とブーストする側のフォロワーを cc に入れる
     *
     * @param objectUri ブーストする投稿の id
     * @param objectAuthor ブーストする投稿の投稿者のアクター id
     * @param followers ブーストする側のフォロワーのコレクションの id
     */
    fun announce(
        id: String,
        actor: String,
        objectUri: String,
        objectAuthor: String,
        followers: String,
        published: Instant,
    ): AnnounceActivity = AnnounceActivity(
        id = id,
        actor = actor,
        published = published.toString(),
        to = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE),
        cc = listOf(objectAuthor, followers).distinct(),
        target = objectUri,
    )

    /**
     * 受け取る側は Undo に埋め込まれた Announce の id で、取り消すブーストを見分ける。[announce] には送ったときと同じものを渡す
     *
     * @param id Announce と同じ id にすると、id で重複を落とす相手に Undo を捨てられる
     */
    fun undo(id: String, announce: AnnounceActivity): OutgoingActivity {
        require(id != announce.id)
        return OutgoingActivity(
            id = id,
            type = "Undo",
            actor = announce.actor,
            target = LinkOrObject.Embedded(AppJson.encodeToJsonElement(announce).jsonObject),
        )
    }
}
