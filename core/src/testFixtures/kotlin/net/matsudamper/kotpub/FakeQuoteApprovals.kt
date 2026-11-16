package net.matsudamper.kotpub

import net.matsudamper.kotpub.actor.RemoteActor
import net.matsudamper.kotpub.quote.QuoteApprovals

/**
 * 引用の承認の配信先の差し替え。登録された `Accept` をオンメモリで持つ
 *
 * @param refusedActorIds 受け付けない相手
 */
class FakeQuoteApprovals(
    private val refusedActorIds: Set<String> = setOf(),
) : QuoteApprovals {
    val enqueued: MutableList<Enqueued> = mutableListOf()

    override fun enqueueAccept(
        username: String,
        quoter: RemoteActor,
        acceptBody: String,
    ): Boolean {
        if (quoter.actorId in refusedActorIds) return false
        enqueued += Enqueued(username = username, inbox = quoter.inbox, acceptBody = acceptBody)
        return true
    }

    data class Enqueued(
        val username: String,
        val inbox: String,
        val acceptBody: String,
    )
}
