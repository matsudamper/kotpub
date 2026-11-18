package net.matsudamper.kotpub.follower

import net.matsudamper.kotpub.actor.ActorDirectory
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.HttpStatusCodes

/**
 * フォロワーの一覧を返す。Actor の `followers` が指している先。
 */
class FollowerEndpoint(
    private val directory: ActorDirectory,
    private val followers: FollowerStore,
) {
    /**
     * `/users/{username}/followers`
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
            collectionId = urls.followers,
            accept = accept,
            cursor = cursor,
            total = followers.count(urls.username),
            list = { after, limit -> followers.list(username = urls.username, after = after, limit = limit) },
        )
    }
}
