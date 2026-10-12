package net.matsudamper.kotpub

import net.matsudamper.kotpub.actor.ActorAppearance
import net.matsudamper.kotpub.actor.StoredActorAppearances

class FakeStoredActorAppearances(
    private val appearances: Map<String, ActorAppearance> = mapOf(),
) : StoredActorAppearances {
    override fun find(username: String): ActorAppearance {
        return appearances[username] ?: ActorAppearance.EMPTY
    }
}
