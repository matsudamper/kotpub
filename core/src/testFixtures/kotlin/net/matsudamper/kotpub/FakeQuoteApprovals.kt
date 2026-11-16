package net.matsudamper.kotpub

import net.matsudamper.kotpub.actor.LocalActor
import net.matsudamper.kotpub.actor.RemoteActor
import net.matsudamper.kotpub.quote.QuoteApprovals

/**
 * 引用の承認の差し替え。[TestLocalActor] だけをこちらのアカウントとして持ち、登録された `Accept` をオンメモリで持つ
 *
 * @param refusedActorIds 引用を認めない相手
 */
class FakeQuoteApprovals(
    private val refusedActorIds: Set<String> = setOf(),
) : QuoteApprovals {
    val enqueued: MutableList<Enqueued> = mutableListOf()

    override fun findAuthor(username: String): LocalActor? =
        TestLocalActor.actor.takeIf { username == TestLocalActor.USERNAME }

    override fun refuses(
        username: String,
        quoterActorId: String,
    ): Boolean = quoterActorId in refusedActorIds

    override fun enqueueAccept(
        username: String,
        quoter: RemoteActor,
        acceptBody: String,
    ) {
        enqueued += Enqueued(username = username, inbox = quoter.inbox, acceptBody = acceptBody)
    }

    data class Enqueued(
        val username: String,
        val inbox: String,
        val acceptBody: String,
    )
}
