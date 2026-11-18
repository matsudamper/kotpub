package net.matsudamper.kotpub.follower

import kotlinx.serialization.builtins.serializer
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.collection.COLLECTION_CURSOR_PARAM
import net.matsudamper.kotpub.collection.COLLECTION_PAGE_SIZE
import net.matsudamper.kotpub.collection.OrderedCollection
import net.matsudamper.kotpub.collection.OrderedCollectionPage
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.QueryParameter

/**
 * アクター URL を URL 順に並べた集合を返す。`followers` と `following` で同じ形にする。
 *
 * `?cursor=` が無ければ総数と最初のページへの入口だけを返し、あればその中身を返す。
 * 2 段構えは ActivityPub の決まりで、Mastodon は総数だけを見ることも、
 * ページを辿って中身を読むこともある。
 *
 * @param collectionId 集合そのものの URL
 * @param cursor クエリの `cursor`。付いていなければ null
 * @param list 位置はアクター URL で指す。null なら先頭から
 */
internal fun actorUriCollectionResponse(
    collectionId: String,
    accept: String?,
    cursor: String?,
    total: Long,
    list: (after: String?, limit: Int) -> List<String>,
): EndpointResponse {
    val contentType = ActivityPubContentTypes.negotiate(accept)

    // パラメータが無ければ集合そのもの。空文字でも付いていれば先頭のページ
    if (cursor == null) {
        return EndpointResponse.json(
            serializer = OrderedCollection.serializer(),
            value = OrderedCollection(
                id = collectionId,
                totalItems = total,
                first = pageUrl(collectionId, null),
            ),
            contentType = contentType,
        )
    }

    val items = list(cursor.ifEmpty { null }, COLLECTION_PAGE_SIZE)

    return EndpointResponse.json(
        serializer = OrderedCollectionPage.serializer(String.serializer()),
        value = OrderedCollectionPage(
            id = pageUrl(collectionId, cursor.ifEmpty { null }),
            totalItems = total,
            partOf = collectionId,
            orderedItems = items,
            // 総数ではなく取得できた件数で判断する。読んでいる間に解除されると
            // 総数の方は減っていることがある
            next = if (items.size < COLLECTION_PAGE_SIZE) null else pageUrl(collectionId, items.last()),
        ),
        contentType = contentType,
    )
}

/**
 * @param after 直前のページの最後のアクター URL。null なら先頭のページ
 */
private fun pageUrl(
    collectionId: String,
    after: String?,
): String = "$collectionId?$COLLECTION_CURSOR_PARAM=${after?.let { QueryParameter.encode(it) }.orEmpty()}"
