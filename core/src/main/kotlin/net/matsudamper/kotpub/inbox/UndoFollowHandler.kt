package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.LinkOrObject
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.actor.ActorDirectory
import net.matsudamper.kotpub.actor.ActorUrls
import net.matsudamper.kotpub.follower.FollowerStore
import net.matsudamper.kotpub.json.AppJson
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
     * id だけの `object` は、記録にあるフォローを解除できなかったら null を返す。
     * まだ届いていない別のアクティビティの取り消しかもしれないので、何もしなかったとは決めない。
     * 埋め込まれた `object` は、ここまでで他の取り消しに当たらなかったものなので、結果を必ず返す
     */
    suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
    ): InboxHandling? {
        val undoneFollow = when (val undoObject = activity.target) {
            null -> return InboxHandling.Ignored("Undo に object が無い: ${recipient.logLabel} ← $verifiedSignerActorId")

            is LinkOrObject.Link -> linkedFollow(recipient, verifiedSignerActorId, undoObject.href) ?: return null

            is LinkOrObject.Embedded -> when (val embedded = embeddedFollow(recipient, verifiedSignerActorId, undoObject.json)) {
                is EmbeddedFollow.Found -> embedded.follow
                is EmbeddedFollow.NotApplicable -> return InboxHandling.Ignored(embedded.reason)
            }
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
            return InboxHandling.Handled
        }
        // 記録が無いのは異常ではない。こちらが Accept を返せなかったフォローや、
        // 既に Delete で消えた相手からも Undo は届く
        return when (activity.target) {
            is LinkOrObject.Embedded -> InboxHandling.Ignored("解除するフォローが記録に無い: ${followee.acct} ← $verifiedSignerActorId")
            else -> null
        }
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
     * `Follow` でなければ [EmbeddedFollow.NotApplicable] を返して呼び出し側に何もさせない。
     * `Follow` だと分かっていて id が無い場合も同じになるが、そのときは
     * [FollowerStore.remove] に null を渡して id を問わずに消す、とは区別が付かない。
     * 区別が要るほどの実装差は見ていないので、id が無ければ消さない方を選ぶ。
     */
    private fun embeddedFollow(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        embeddedObjectJson: JsonObject,
    ): EmbeddedFollow {
        val embeddedFollowActivity =
            runCatching { AppJson.decodeFromJsonElement(InboxActivity.serializer(), embeddedObjectJson) }.getOrNull()
                ?: return EmbeddedFollow.NotApplicable("Undo の object を読めなかった: ${recipient.logLabel} ← $verifiedSignerActorId")

        // Undo{Like} などをフォロー解除として扱わない
        if (embeddedFollowActivity.type != FOLLOW_TYPE) {
            return EmbeddedFollow.NotApplicable(
                "Undo の対象が Follow ではないので何もしない: type=${embeddedFollowActivity.type} " +
                    "${recipient.logLabel} ← $verifiedSignerActorId",
            )
        }

        val followActivityUri = embeddedFollowActivity.id
            ?: return EmbeddedFollow.NotApplicable("Undo の対象の Follow に id が無い: ${recipient.logLabel} ← $verifiedSignerActorId")

        val followTarget = embeddedFollowActivity.target?.id
        val followee = when (recipient) {
            is InboxRecipient.Account -> {
                // 別のアクター宛の Follow を取り消す Undo は、こちらのフォローとは関係が無い
                val resolvedTarget = followTarget?.let { directory.resolveActorId(it) }
                if (followTarget != null && resolvedTarget?.actorId != recipient.urls.actorId) {
                    return EmbeddedFollow.NotApplicable("Undo の対象が別のアクターへの Follow: object=$followTarget 宛先=${recipient.urls.actorId}")
                }
                recipient.urls
            }

            InboxRecipient.Shared -> followTarget?.let { directory.resolveActorId(it) }
        }
        if (followee == null) {
            return EmbeddedFollow.NotApplicable("Undo の対象がこちらのアカウントへの Follow ではない: object=$followTarget ${recipient.logLabel}")
        }

        return EmbeddedFollow.Found(UndoneFollow(followee = followee, followActivityUri = followActivityUri))
    }

    private sealed interface EmbeddedFollow {
        data class Found(val follow: UndoneFollow) : EmbeddedFollow

        data class NotApplicable(val reason: String) : EmbeddedFollow
    }

    private data class UndoneFollow(
        val followee: ActorUrls,
        val followActivityUri: String,
    )

    private companion object {
        const val FOLLOW_TYPE = "Follow"
    }
}
