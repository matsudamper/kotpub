package net.matsudamper.kotpub.like

import java.time.Instant
import kotlinx.serialization.builtins.serializer
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.actor.ActorDirectory
import net.matsudamper.kotpub.actor.ActorUrls
import net.matsudamper.kotpub.actor.StoredActorProfiles
import net.matsudamper.kotpub.collection.COLLECTION_CURSOR_PARAM
import net.matsudamper.kotpub.collection.COLLECTION_PAGE_SIZE
import net.matsudamper.kotpub.collection.CollectionCursor
import net.matsudamper.kotpub.collection.OrderedCollection
import net.matsudamper.kotpub.collection.OrderedCollectionPage
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.QueryParameter

/**
 * いいねした投稿の保存先。[net.matsudamper.kotpub.note.NoteStore] と同じく、実装は利用側のアプリが用意する
 */
interface LikedObjectStore {
    /**
     * いいねした新しい順に返す。公開している投稿へのいいねだけを返す。
     * Actor の `liked` は誰でも読めるので、フォロワー限定の投稿を載せると読めない相手にも投稿の id が知られる
     *
     * @param after 直前のページの最後の位置。null なら最新から
     */
    fun list(
        username: String,
        after: LikedPosition?,
        limit: Int,
    ): List<LikedObject>

    fun count(username: String): Long
}

/**
 * @param objectUri いいねした投稿の id
 */
data class LikedObject(
    val objectUri: String,
    val position: LikedPosition,
)

/**
 * いいねの一覧の続きを指す位置
 *
 * @param at いいねした時刻
 * @param key 同じ時刻のいいねを並べる文字列
 */
data class LikedPosition(
    val at: Instant,
    val key: String,
)

/**
 * いいねした投稿の一覧を返す。Actor の `liked` が指している先。
 *
 * 公開していないアカウントは、知らないアカウントと同じく 404 にする。`liked` を出していない相手に、一覧があることを教えない
 */
class LikedEndpoint(
    private val directory: ActorDirectory,
    private val profiles: StoredActorProfiles,
    private val likedObjects: LikedObjectStore,
) {
    /**
     * `/users/{username}/liked`
     *
     * @param cursor クエリの `cursor`。付いていなければ null
     */
    fun get(
        username: String?,
        accept: String?,
        cursor: String?,
    ): EndpointResponse {
        val urls = directory.resolve(username)
            ?.takeIf { profiles.find(it.username).likedPublic }
            ?: return EndpointResponse.text(
                status = HttpStatusCodes.NOT_FOUND,
                text = "いいねの一覧が見つからない: $username",
                headers = mapOf(),
            )

        val contentType = ActivityPubContentTypes.negotiate(accept)
        val total = likedObjects.count(urls.username)

        // パラメータが無ければ集合そのもの。空文字でも付いていれば先頭のページ
        if (cursor == null) {
            return EndpointResponse.json(
                serializer = OrderedCollection.serializer(),
                value = OrderedCollection(
                    id = urls.liked,
                    totalItems = total,
                    first = pageUrl(urls, null),
                ),
                contentType = contentType,
            )
        }

        // 読めない cursor は先頭のページとして扱う。相手が辿るだけの値なので、壊れていることを教えても直しようが無い
        val after = cursor.ifEmpty { null }?.let { CollectionCursor.decode(it) }?.let { LikedPosition(at = it.at, key = it.key) }
        val page = likedObjects.list(username = urls.username, after = after, limit = COLLECTION_PAGE_SIZE)

        return EndpointResponse.json(
            serializer = OrderedCollectionPage.serializer(String.serializer()),
            value = OrderedCollectionPage(
                id = pageUrl(urls, after),
                totalItems = total,
                partOf = urls.liked,
                orderedItems = page.map { it.objectUri },
                // 総数ではなく取得できた件数で判断する。読んでいる間に増えていることがある
                next = if (page.size < COLLECTION_PAGE_SIZE) null else pageUrl(urls, page.last().position),
            ),
            contentType = contentType,
        )
    }

    /**
     * @param after 直前のページの最後の位置。null なら先頭のページ
     */
    private fun pageUrl(
        urls: ActorUrls,
        after: LikedPosition?,
    ): String {
        val encoded = after?.let { QueryParameter.encode(CollectionCursor(at = it.at, key = it.key).encode()) }.orEmpty()
        return "${urls.liked}?$COLLECTION_CURSOR_PARAM=$encoded"
    }
}
