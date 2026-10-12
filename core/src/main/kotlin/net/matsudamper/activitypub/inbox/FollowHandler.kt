package net.matsudamper.activitypub.inbox

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import net.matsudamper.activitypub.activity.InboxActivity
import net.matsudamper.activitypub.activity.OutgoingActivity
import net.matsudamper.activitypub.activitypub.LinkOrObject
import net.matsudamper.activitypub.activitypub.id
import net.matsudamper.activitypub.actor.ActorDirectory
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.actor.RemoteActors
import net.matsudamper.activitypub.follower.FollowerStore
import net.matsudamper.activitypub.json.AppJson
import org.slf4j.LoggerFactory

/**
 * `Follow` を受けたときの処理。
 *
 * ActivityPub のフォローは、相手が `Follow` を送ってきた時点では成立していない。
 * こちらが `Accept` を相手の inbox に返して初めて確定する。返さないと
 * Mastodon の画面ではフォローボタンが保留のまま戻らない。
 *
 * ここでは送らない。記録と一緒に `Accept` を [FollowerStore] に預けて、
 * inbox の応答を返す。相手のサーバーが応答しないときに inbox が待たされることも、
 * 1 回送れなかっただけで保留が残ることも無くなる。
 *
 * フォローが成立するのは `Accept` が届いたときなので、フォロワーとして数え始めるのも
 * そこから。預けた側からは成立の時点が見えないので、成立してから配る過去の投稿は
 * ここでは扱わない。
 */
class FollowHandler(
    private val directory: ActorDirectory,
    private val remoteActors: RemoteActors,
    private val followers: FollowerStore,
) : InboxActivityHandler {
    override val type: String = "Follow"

    private val logger = LoggerFactory.getLogger(FollowHandler::class.java)

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ) {
        val followTargetActorId = activity.target?.id
        val followee = followTargetActorId?.let { directory.resolveActorId(it) }
        if (followee == null) {
            logger.warn("Follow の宛先がこちらのアカウントではないので Accept を返さない: object=$followTargetActorId ${recipient.logLabel}")
            return
        }

        // 宛先の異なる Follow をこちらの inbox に投げ込むことはできる。
        // 中身を見ずに Accept を返すと、フォローしていないアクターの
        // フォローが成立したように相手に見える
        if (recipient is InboxRecipient.Account && followee.actorId != recipient.urls.actorId) {
            logger.warn("Follow の宛先が違うので Accept を返さない: object=$followTargetActorId 宛先=${recipient.urls.actorId}")
            return
        }

        // 相手が `id` を付けずに送ってくると、同じ `Follow` の送り直しと
        // 新しい `Follow` を区別できない。区別できないものを記録すると、
        // 送り直しのたびに行が増えるか、別のフォローを取り違えて消すことになる
        val followActivityUri = activity.id
        if (followActivityUri == null) {
            logger.warn("Follow に id が無いので受け付けない: ${followee.acct} ← $verifiedSignerActorId")
            return
        }

        val follower = remoteActors.findActor(verifiedSignerActorId)
        if (follower == null) {
            logger.warn("Follow に Accept を返せなかった: ${followee.acct} ← $verifiedSignerActorId フォロワーのアクターを引けない")
            return
        }

        val accept =
            OutgoingActivity(
                id = acceptId(followee),
                type = OutgoingActivity.TYPE_ACCEPT,
                actor = followee.actorId,
                target = LinkOrObject.Embedded(rawActivityJson),
            )

        val recorded = runCatching {
            followers.record(
                username = followee.username,
                follower = follower,
                followActivityUri = followActivityUri,
                receivedAt = Instant.now(),
                acceptBody = AppJson.encodeToString(OutgoingActivity.serializer(), accept),
            )
        }
        if (recorded.isFailure) {
            // 記録できていないので Accept も投函されていない。相手には保留のまま見えるが、
            // Follow は送り直されるので次の機会がある
            logger.warn(
                "Follow を記録できなかったので Accept を返さない: ${followee.acct} ← $verifiedSignerActorId",
                recorded.exceptionOrNull(),
            )
            return
        }

        // 引き当てた後に消されたアカウント宛。返す先のアカウントがもう無い
        if (recorded.getOrDefault(false).not()) {
            logger.info("消えたアカウント宛の Follow なので受け付けない: ${followee.acct} ← $verifiedSignerActorId")
            return
        }

        logger.info("Follow を記録して Accept を投函した: ${followee.acct} ← $verifiedSignerActorId")
    }

    private companion object {
        /**
         * `Accept` 自身の id。
         *
         * アクター id にフラグメントを付けた形にする。相手はこの URL を取りに来ないが、
         * 独立したパスにすると「GET できる文書がある」と読める形になり、
         * 実際には返せないものを配ることになる。Mastodon も同じ作りで送ってくる。
         */
        fun acceptId(followee: ActorUrls): String = "${followee.actorId}#accepts/follows/${UUID.randomUUID()}"
    }
}
