package net.matsudamper.kotpub.inbox

import kotlinx.serialization.json.JsonObject
import net.matsudamper.kotpub.activity.InboxActivity
import net.matsudamper.kotpub.activitypub.id
import net.matsudamper.kotpub.actor.RemoteActorLookup
import net.matsudamper.kotpub.actor.RemoteActors
import net.matsudamper.kotpub.follower.MovedActorFollowings
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.RemoteFetchFailure

/**
 * `Move` を受けたときの処理。引っ越したアカウントのフォローを移り先へ付け替える。
 *
 * Mastodon は引っ越すと、移り元のフォロワーのサーバーへ `actor` と `object` が移り元、
 * `target` が移り先の `Move` を送ってくる。
 *
 * 移り元の署名だけでは、他人のアカウントを移り先に名乗れてしまう。移り先のアクター文書を
 * 取得して、移り元を `alsoKnownAs` に入れているときだけ本人の引っ越しとして扱う。
 * 埋め込まれた移り先は移り元が書いたものなので読まない。
 *
 * こちらのどのアカウントがフォローしていたかに関わらず付け替える。`Move` は共有 inbox に
 * 1 通だけ届くことがあり、宛先のアカウントだけを付け替えると他のアカウントの分が残る。
 */
class MoveHandler(
    private val remoteActors: RemoteActors,
    private val followings: MovedActorFollowings,
) : InboxActivityHandler {
    override val type: String = "Move"

    override suspend fun handle(
        recipient: InboxRecipient,
        verifiedSignerActorId: String,
        activity: InboxActivity,
        rawActivityJson: JsonObject,
    ): InboxHandling {
        val movedFromActorId = activity.target?.id
        if (movedFromActorId != verifiedSignerActorId) {
            return InboxHandling.Ignored("Move の移り元が送り主自身ではないので何もしない: object=$movedFromActorId 署名者=$verifiedSignerActorId")
        }

        val movedToActorId = activity.destination?.id
        if (movedToActorId == null) {
            return InboxHandling.Ignored("Move に target が無い: ${recipient.logLabel} ← $verifiedSignerActorId")
        }

        val movedTo = when (val lookup = remoteActors.findActor(movedToActorId)) {
            is RemoteActorLookup.Found -> lookup.actor

            RemoteActorLookup.Gone, is RemoteActorLookup.Unavailable -> {
                // 落とすと付け替えが失われるので、一時的な失敗なら送り直してもらう
                if (lookup is RemoteActorLookup.Unavailable && lookup.failure.isTemporary()) {
                    throw IllegalStateException("Move の移り先のアクターを一時的に取得できない: $verifiedSignerActorId → $movedToActorId 理由=$lookup")
                }
                return InboxHandling.Ignored("Move の移り先のアクターを取得できない: $verifiedSignerActorId → $movedToActorId 理由=$lookup")
            }
        }

        // 取得先と同じホストなら、文書は別のアクターの id を名乗れる。名乗られたアクター自身が
        // 移り元を別名に入れているとは限らないので、取得した URL そのもののアクターだけを信じる
        if (movedTo.actorId != movedToActorId) {
            return InboxHandling.Ignored("Move の移り先の文書が別のアクターを名乗っている: target=$movedToActorId id=${movedTo.actorId}")
        }

        if (movedTo.actorId == movedFromActorId) {
            return InboxHandling.Ignored("Move の移り先が移り元と同じ: $verifiedSignerActorId")
        }

        if (movedFromActorId !in movedTo.alsoKnownAs) {
            return InboxHandling.Ignored("Move の移り先が移り元を alsoKnownAs に入れていない: $verifiedSignerActorId → ${movedTo.actorId}")
        }

        followings.move(movedFromActorId = movedFromActorId, movedTo = movedTo)
        return InboxHandling.Handled
    }

    /**
     * [RemoteFetchFailure.BodyUnreadable] は本文を読む途中の切断やタイムアウト。本文が大きすぎるものは
     * [RemoteFetchFailure.BodyTooLarge] で別に来るので、送り直されても同じ結果にはならない
     */
    private fun RemoteFetchFailure.isTemporary(): Boolean =
        when (this) {
            is RemoteFetchFailure.Throttled, is RemoteFetchFailure.RequestFailed, is RemoteFetchFailure.BodyUnreadable -> true
            is RemoteFetchFailure.UnsuccessfulStatus -> status == HttpStatusCodes.TOO_MANY_REQUESTS || status >= HttpStatusCodes.INTERNAL_SERVER_ERROR
            else -> false
        }
}
