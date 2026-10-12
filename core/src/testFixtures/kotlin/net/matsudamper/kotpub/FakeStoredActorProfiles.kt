package net.matsudamper.kotpub

import net.matsudamper.kotpub.actor.ActorProfile
import net.matsudamper.kotpub.actor.StoredActorProfiles

class FakeStoredActorProfiles(
    private val profiles: Map<String, ActorProfile> = mapOf(),
) : StoredActorProfiles {
    override fun find(username: String): ActorProfile {
        return profiles[username] ?: ActorProfile.EMPTY
    }
}
