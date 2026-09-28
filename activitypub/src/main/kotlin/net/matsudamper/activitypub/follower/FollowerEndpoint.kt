package net.matsudamper.activitypub.follower

import kotlinx.serialization.builtins.serializer
import net.matsudamper.activitypub.activitypub.ActivityPubContentTypes
import net.matsudamper.activitypub.actor.ActorDirectory
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.collection.COLLECTION_CURSOR_PARAM
import net.matsudamper.activitypub.collection.COLLECTION_PAGE_SIZE
import net.matsudamper.activitypub.collection.OrderedCollection
import net.matsudamper.activitypub.collection.OrderedCollectionPage
import net.matsudamper.activitypub.http.EndpointResponse
import net.matsudamper.activitypub.http.HttpStatusCodes
import net.matsudamper.activitypub.http.QueryParameter

/**
 * フォロワーの一覧を返す。Actor の `followers` が指している先。
 *
 * `?cursor=` が無ければ総数と最初のページへの入口だけを返し、あればその中身を返す。
 * 2 段構えは ActivityPub の決まりで、Mastodon は総数だけを見ることも、
 * ページを辿って中身を読むこともある。
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

        val contentType = ActivityPubContentTypes.negotiate(accept)
        val total = followers.count(urls.username)

        // パラメータが無ければ集合そのもの。空文字でも付いていれば先頭のページ
        if (cursor == null) {
            return EndpointResponse.json(
                serializer = OrderedCollection.serializer(),
                value = OrderedCollection(
                    id = urls.followers,
                    totalItems = total,
                    first = pageUrl(urls, null),
                ),
                contentType = contentType,
            )
        }

        val items = followers.list(
            username = urls.username,
            after = cursor.ifEmpty { null },
            limit = COLLECTION_PAGE_SIZE,
        )

        return EndpointResponse.json(
            serializer = OrderedCollectionPage.serializer(String.serializer()),
            value = OrderedCollectionPage(
                id = pageUrl(urls, cursor.ifEmpty { null }),
                totalItems = total,
                partOf = urls.followers,
                orderedItems = items,
                // 総数ではなく取れた件数で判断する。読んでいる間に解除されると
                // 総数の方は減っていることがある
                next = if (items.size < COLLECTION_PAGE_SIZE) null else pageUrl(urls, items.last()),
            ),
            contentType = contentType,
        )
    }
}

/**
 * @param after 直前のページの最後のアクター URL。null なら先頭のページ
 */
private fun pageUrl(
    urls: ActorUrls,
    after: String?,
): String = "${urls.followers}?$COLLECTION_CURSOR_PARAM=${after?.let { QueryParameter.encode(it) }.orEmpty()}"
