package net.matsudamper.activitypub

import java.time.Instant
import net.matsudamper.activitypub.inbox.EarlyUndoneLikes

class FakeEarlyUndoneLikes : EarlyUndoneLikes {
    private val expiresAt = mutableMapOf<Pair<String, String>, Instant>()

    override fun remember(
        actorUri: String,
        activityUri: String,
        expiresAt: Instant,
    ) {
        this.expiresAt[actorUri to activityUri] = expiresAt
    }

    override fun isRemembered(
        actorUri: String,
        activityUri: String,
        now: Instant,
    ): Boolean = expiresAt[actorUri to activityUri]?.isAfter(now) == true
}
