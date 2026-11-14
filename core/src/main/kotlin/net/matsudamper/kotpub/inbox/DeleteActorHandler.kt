package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.boost.BoostStore
import net.matsudamper.kotpub.favourite.FavouriteStore
import net.matsudamper.kotpub.follower.FollowerStore
import net.matsudamper.kotpub.stamp.StampStore
import org.slf4j.LoggerFactory

/**
 * `Delete` を受けたときの処理。アカウントが消えたフォロワーを掃除する。
 *
 * `Delete` は投稿の削除にも使われるので、`object` が送り主自身を指しているものだけを
 * アカウントの削除として扱う。Mastodon はアカウントの削除と引っ越しのどちらでも
 * この形で送ってくる。
 *
 * 掃除しないと、消えたアカウントの inbox に投稿を送り続けることになる。
 *
 * ここに届くのは署名を検証できたものだけ。相手が既に消えていてアクター文書を
 * 取得できなくても、フォローとお気に入りとスタンプとブーストのどれを受けたときも相手の公開鍵を記録しているので
 * 検証は通る。
 * 記録の無い相手からの `Delete` は [InboxService] が検証の手前で落とすが、
 * 記録が無い以上、掃除するものも無い。
 */
class DeleteActorHandler(
    private val followers: FollowerStore,
    private val favourites: FavouriteStore,
    private val stamps: StampStore,
    private val boosts: BoostStore,
) : InboxActivityHandler {
    override val type: String = "Delete"

    private val logger = LoggerFactory.getLogger(DeleteActorHandler::class.java)

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        val deleteObjectId = activity.target?.id
        if (deleteObjectId == null) {
            return InboxHandling.Ignored("Delete に object が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
        }

        if (deleteObjectId != verifiedSignerActorId) {
            return InboxHandling.Ignored("Delete の対象がアクター自身ではないので何もしない: object=$deleteObjectId 署名者=$verifiedSignerActorId")
        }

        // お気に入りとスタンプとブーストを先に消す。フォローの解除は相手のアクターの記録ごと消すので、
        // 順番を入れ替えると道連れで消えて、件数だけが 0 になる
        val removedFavourites = favourites.removeActor(deleteObjectId)
        val removedStamps = stamps.removeActor(deleteObjectId)
        val removedBoosts = boosts.removeActor(deleteObjectId)

        // こちらのどのアカウントをフォローしていたかに関わらず全部消える。
        // 宛先のアカウントだけを消すと、同じ相手が他のアカウントをフォローしていた分が
        // 残り、消えた相手に送り続けることになる
        val removed = followers.removeRemoteActor(deleteObjectId)

        logger.info(
            "アクターが削除されたので記録から外した: $deleteObjectId " +
                "解除したフォロー=$removed 件 取り消したお気に入り=$removedFavourites 件 取り消したスタンプ=$removedStamps 件 取り消したブースト=$removedBoosts 件",
        )
        return InboxHandling.Handled
    }
}
