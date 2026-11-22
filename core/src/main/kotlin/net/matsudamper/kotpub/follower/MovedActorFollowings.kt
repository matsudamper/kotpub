package net.matsudamper.kotpub.follower

import net.matsudamper.kotpub.actor.RemoteActor

/**
 * 相手のアカウントが引っ越したときに、こちらのフォローを移り先へ付け替える先。
 *
 * 呼ばれる時点で、移り先が移り元を別名に入れていることは確かめてある。
 */
interface MovedActorFollowings {
    /**
     * [movedFromActorId] をフォローしているこちらのアカウントに、[movedTo] をフォローさせ、
     * [movedFromActorId] のフォローを外す。フォローしているアカウントが無ければ何もしない
     */
    suspend fun move(
        movedFromActorId: String,
        movedTo: RemoteActor,
    )
}
