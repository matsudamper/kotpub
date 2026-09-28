package net.matsudamper.activitypub

import net.matsudamper.activitypub.actor.FeedLinks
import net.matsudamper.activitypub.actor.StoredFeedLinks

class FakeStoredFeedLinks(
    private val links: Map<String, FeedLinks> = mapOf(),
) : StoredFeedLinks {
    override fun find(username: String): FeedLinks {
        return links[username] ?: FeedLinks.EMPTY
    }
}
