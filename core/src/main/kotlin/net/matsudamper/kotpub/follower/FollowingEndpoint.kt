package net.matsudamper.kotpub.follower

import net.matsudamper.kotpub.actor.ActorDirectory
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.HttpStatusCodes

/**
 * フォローしている相手の一覧を返す。Actor の `following` が指している先。
 */
class FollowingEndpoint(
    private val directory: ActorDirectory,
    private val following: FollowingStore,
) {
    /**
     * `/users/{username}/following`
     *
     * @param cursor クエリの `cursor`。付いていなければ null
     */
    suspend fun get(
        username: String?,
        accept: String?,
        cursor: String?,
    ): EndpointResponse {
        val urls = directory.resolve(username)
            ?: return EndpointResponse.text(
                status = HttpStatusCodes.NOT_FOUND,
                text = "アカウントが見つからない: $username",
                headers = mapOf(),
            )

        return actorUriCollectionResponse(
            collectionId = urls.following,
            accept = accept,
            cursor = cursor,
            total = following.count(urls.username),
            list = { after, limit -> following.list(username = urls.username, after = after, limit = limit) },
        )
    }
}
