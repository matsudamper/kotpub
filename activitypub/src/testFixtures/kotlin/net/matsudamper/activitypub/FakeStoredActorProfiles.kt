package net.matsudamper.activitypub

import net.matsudamper.activitypub.actor.ActorProfile
import net.matsudamper.activitypub.actor.StoredActorProfiles

class FakeStoredActorProfiles(
    private val profiles: Map<String, ActorProfile> = mapOf(),
) : StoredActorProfiles {
    override fun find(username: String): ActorProfile {
        return profiles[username] ?: ActorProfile.EMPTY
    }
}
