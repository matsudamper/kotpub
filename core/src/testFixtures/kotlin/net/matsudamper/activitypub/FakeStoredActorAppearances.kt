package net.matsudamper.activitypub

import net.matsudamper.activitypub.actor.ActorAppearance
import net.matsudamper.activitypub.actor.StoredActorAppearances

class FakeStoredActorAppearances(
    private val appearances: Map<String, ActorAppearance> = mapOf(),
) : StoredActorAppearances {
    override fun find(username: String): ActorAppearance {
        return appearances[username] ?: ActorAppearance.EMPTY
    }
}
