package net.matsudamper.kotpub.follower

interface FollowingResponses {
    fun accept(actorId: String, followActivityId: String)
    fun reject(actorId: String, followActivityId: String)
}
