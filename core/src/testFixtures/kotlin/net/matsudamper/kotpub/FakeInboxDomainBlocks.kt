package net.matsudamper.kotpub

import net.matsudamper.kotpub.inbox.InboxDomainBlocks

/**
 * @param blockedUrls 受信を止める URL。渡された URL そのものと比べる
 */
class FakeInboxDomainBlocks(
    private val blockedUrls: Set<String>,
) : InboxDomainBlocks {
    val signedRequestSigners: MutableList<String> = mutableListOf()

    override fun blocksInboxFrom(url: String): Boolean = url in blockedUrls

    override fun signedRequestReceived(verifiedSignerActorId: String) {
        signedRequestSigners += verifiedSignerActorId
    }
}
