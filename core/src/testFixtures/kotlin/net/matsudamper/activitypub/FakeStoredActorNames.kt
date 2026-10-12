package net.matsudamper.activitypub

import net.matsudamper.activitypub.actor.StoredActorNames

class FakeStoredActorNames(
    private val storedUserNames: List<String>,
) : StoredActorNames {
    override fun find(username: String): String? {
        return storedUserNames.find {
            it.equals(username, ignoreCase = true)
        }
    }

    override fun finds(usernames: Set<String>): Map<String, String> {
        return buildMap {
            for (username in usernames) {
                val found = find(username) ?: continue
                put(username, found)
            }
        }
    }
}
