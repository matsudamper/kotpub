package net.matsudamper.activitypub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.activitypub.LinkOrObject
import net.matsudamper.activitypub.activitypub.id
import net.matsudamper.activitypub.actor.ActorDirectory
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.follower.FollowerStore
import net.matsudamper.activitypub.json.AppJson
import org.slf4j.LoggerFactory

/**
 * `object` が `Follow` だったときだけ消す。相手の実装によって、`object` に
 * `Follow` が丸ごと埋まっていることも、その id だけが入っていることもある。
 *
 * 埋まっている場合は `type` を見れば `Follow` だと分かる。id だけの場合は
 * 何のアクティビティの id なのか分からないので、こちらが記録している
 * `Follow` の id と一致するかどうかで判断する。一致しなければ何もしない。
 *
 * `Accept` は返さない。`Undo` に応答を返す決まりは無く、Mastodon も返さない。
 */
class UndoFollowHandler(
    private val directory: ActorDirectory,
    private val followers: FollowerStore,
) {
    private val logger = LoggerFactory.getLogger(UndoFollowHandler::class.java)

    /**
     * @return フォローを解除したら true
     */
    suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
    ): Boolean {
        val undoneFollow = when (val undoObject = activity.target) {
            null -> {
                logger.warn("Undo に object が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
                return false
            }

            is LinkOrObject.Link -> linkedFollow(recipient, verifiedSignerActorId, undoObject.href) ?: return false

            is LinkOrObject.Embedded -> embeddedFollow(recipient, verifiedSignerActorId, undoObject.json) ?: return false
        }
        val followee = undoneFollow.followee

        // 消せるのは署名した本人のフォローだけ。他人のフォローを消す Undo は
        // 名前を差し替えれば書けてしまうので、相手は署名の持ち主で固定する
        val removed = followers.remove(
            username = followee.username,
            followerActorUri = verifiedSignerActorId,
            followActivityUri = undoneFollow.followActivityUri,
        )

        if (removed) {
            logger.info("フォローを解除した: ${followee.acct} ← $verifiedSignerActorId")
        } else {
            // 記録が無いのは異常ではない。こちらが Accept を返せなかったフォローや、
            // 既に Delete で消えた相手からも Undo は届く
            logger.info("解除するフォローが記録に無い: ${followee.acct} ← $verifiedSignerActorId")
        }
        return removed
    }

    private fun linkedFollow(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        followActivityUri: String,
    ): UndoneFollow? {
        val followee = when (recipient) {
            is InboxRecipient.Account -> recipient.urls

            InboxRecipient.Shared ->
                followers
                    .findFolloweeUsername(followerActorUri = verifiedSignerActorId, followActivityUri = followActivityUri)
                    ?.let { directory.resolve(it) }
        } ?: return null

        return UndoneFollow(followee = followee, followActivityUri = followActivityUri)
    }

    /**
     * 埋め込まれた `object` を `Follow` として読み、その id と宛先のアカウントを返す。
     *
     * `Follow` でなければ null を返して呼び出し側に何もさせない。
     * `Follow` だと分かっていて id が無い場合も null になるが、そのときは
     * [FollowerStore.remove] に null を渡して id を問わずに消す、とは区別が付かない。
     * 区別が要るほどの実装差は見ていないので、id が無ければ消さない側に倒す。
     */
    private fun embeddedFollow(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        embeddedObjectJson: JsonObject,
    ): UndoneFollow? {
        val embeddedFollowActivity =
            runCatching { AppJson.decodeFromJsonElement(InboxActivity.serializer(), embeddedObjectJson) }.getOrNull()
        if (embeddedFollowActivity == null) {
            logger.warn("Undo の object を読めなかった: ${recipient.logLabel} ← $verifiedSignerActorId")
            return null
        }

        // Undo{Like} などをフォロー解除として扱わない
        if (embeddedFollowActivity.type != FOLLOW_TYPE) {
            logger.info(
                "Undo の対象が Follow ではないので何もしない: type=${embeddedFollowActivity.type} " +
                    "${recipient.logLabel} ← $verifiedSignerActorId",
            )
            return null
        }

        val followActivityUri = embeddedFollowActivity.id ?: return null

        val followTarget = embeddedFollowActivity.target?.id
        val followee = when (recipient) {
            is InboxRecipient.Account -> {
                // 別のアクター宛の Follow を取り消す Undo は、こちらのフォローとは関係が無い
                val resolvedTarget = followTarget?.let { directory.resolveActorId(it) }
                if (followTarget != null && resolvedTarget?.actorId != recipient.urls.actorId) {
                    logger.info("Undo の対象が別のアクターへの Follow: object=$followTarget 宛先=${recipient.urls.actorId}")
                    return null
                }
                recipient.urls
            }

            InboxRecipient.Shared -> followTarget?.let { directory.resolveActorId(it) }
        }
        if (followee == null) {
            logger.info("Undo の対象がこちらのアカウントへの Follow ではない: object=$followTarget ${recipient.logLabel}")
            return null
        }

        return UndoneFollow(followee = followee, followActivityUri = followActivityUri)
    }

    private data class UndoneFollow(
        val followee: ActorUrls,
        val followActivityUri: String,
    )

    private companion object {
        const val FOLLOW_TYPE = "Follow"
    }
}
