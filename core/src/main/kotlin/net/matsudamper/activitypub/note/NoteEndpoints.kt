package net.matsudamper.activitypub.note

import java.time.Instant
import net.matsudamper.activitypub.activity.ActivityStreamsIri
import net.matsudamper.activitypub.activity.CreateNoteActivity
import net.matsudamper.activitypub.activitypub.ActivityPubContentTypes
import net.matsudamper.activitypub.actor.ActorDirectory
import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.collection.COLLECTION_CURSOR_PARAM
import net.matsudamper.activitypub.collection.COLLECTION_PAGE_SIZE
import net.matsudamper.activitypub.collection.OrderedCollection
import net.matsudamper.activitypub.collection.OrderedCollectionPage
import net.matsudamper.activitypub.collection.OrderedCollectionWithItems
import net.matsudamper.activitypub.entity.PublicNoteId
import net.matsudamper.activitypub.http.EndpointResponse
import net.matsudamper.activitypub.http.HttpStatusCodes
import net.matsudamper.activitypub.http.QueryParameter
import net.matsudamper.activitypub.url.WebPageUrls

/**
 * 配信した投稿を返す。
 *
 * 相手は受け取った `Create` の `object.id` をパーマリンクとして引きに来る。
 * ここが 404 だと、タイムラインには出ていても開けない投稿になる。
 */
class NoteEndpoint(
    private val domain: String,
    private val notes: NoteStore,
    private val webPages: WebPageUrls?,
) {
    /**
     * `/notes/{publicId}`
     */
    suspend fun get(
        publicId: String?,
        accept: String?,
    ): EndpointResponse {
        val note = publicId?.let { notes.find(PublicNoteId(it)) }
            ?: return notFound("投稿が見つからない: $publicId")

        return EndpointResponse.json(
            serializer = Note.serializer(),
            value = noteDocument(
                urls = ActorUrls(domain = domain, username = note.username),
                note = note,
                embedded = false,
                webPages = webPages,
            ),
            contentType = ActivityPubContentTypes.negotiate(accept),
        )
    }
}

/**
 * 配信した投稿の一覧を返す。Actor の `outbox` が指している先。
 *
 * 中身は投稿そのものではなく、配信したときと同じ `Create` を並べる。
 * `outbox` はアクティビティの記録であって投稿の一覧ではない、というのが
 * ActivityPub の決まり。
 */
class OutboxEndpoint(
    private val directory: ActorDirectory,
    private val notes: NoteStore,
    private val webPages: WebPageUrls?,
) {
    /**
     * `/users/{username}/outbox`
     *
     * @param cursor クエリの `cursor`。付いていなければ null
     */
    suspend fun get(
        username: String?,
        accept: String?,
        cursor: String?,
    ): EndpointResponse {
        val urls = directory.resolve(username)
            ?: return notFound("アカウントが見つからない: $username")

        val contentType = ActivityPubContentTypes.negotiate(accept)
        val total = notes.count(urls.username)

        // パラメータが無ければ集合そのもの。空文字でも付いていれば先頭のページ
        if (cursor == null) {
            return EndpointResponse.json(
                serializer = OrderedCollection.serializer(),
                value = OrderedCollection(
                    id = urls.outbox,
                    totalItems = total,
                    first = pageUrl(urls, null),
                ),
                contentType = contentType,
            )
        }

        val page = notes.list(
            username = urls.username,
            // 読めない cursor は先頭に倒す。相手が辿るだけの値なので、
            // 壊れていることを教えても直しようが無い
            after = cursor.ifEmpty { null }?.let { decodeCursor(it) },
            limit = COLLECTION_PAGE_SIZE,
        )

        val items = page
            .map { note ->
                CreateNoteActivity(
                    id = NoteUrls(domain = urls.domain, publicId = note.publicId).createId,
                    actor = urls.actorId,
                    published = note.publishedAt.toActivityPubPublished(),
                    to = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE),
                    cc = listOf(urls.followers),
                    target = noteDocument(urls = urls, note = note, embedded = true, webPages = webPages),
                )
            }

        return EndpointResponse.json(
            serializer = OrderedCollectionPage.serializer(CreateNoteActivity.serializer()),
            value = OrderedCollectionPage(
                id = pageUrl(urls, cursor.ifEmpty { null }?.let { decodeCursor(it) }),
                totalItems = total,
                partOf = urls.outbox,
                orderedItems = items,
                // 総数ではなく取れた件数で判断する。読んでいる間に増えていることがある
                next = if (page.size < COLLECTION_PAGE_SIZE) null else pageUrl(urls, page.last().position),
            ),
            contentType = contentType,
        )
    }
}

/**
 * プロフィールに載せる投稿の一覧。Actor の `featured` が指している先。
 *
 * いまは空を返す。投稿を自動でピン留めしない。載せる処理は将来ここに足す。
 */
class FeaturedEndpoint(
    private val directory: ActorDirectory,
) {
    /**
     * `/users/{username}/collections/featured`
     */
    suspend fun get(
        username: String?,
        accept: String?,
    ): EndpointResponse {
        val urls = directory.resolve(username)
            ?: return notFound("アカウントが見つからない: $username")

        return EndpointResponse.json(
            serializer = OrderedCollectionWithItems.serializer(Note.serializer()),
            value = OrderedCollectionWithItems(
                id = urls.featured,
                totalItems = 0,
                orderedItems = emptyList(),
            ),
            contentType = ActivityPubContentTypes.negotiate(accept),
        )
    }
}

private fun notFound(text: String): EndpointResponse =
    EndpointResponse.text(status = HttpStatusCodes.NOT_FOUND, text = text, headers = mapOf())

/**
 * @param after 直前のページの最後の位置。null なら先頭のページ
 */
private fun pageUrl(
    urls: ActorUrls,
    after: NotePosition?,
): String = "${urls.outbox}?$COLLECTION_CURSOR_PARAM=${after?.let { QueryParameter.encode(it.encodeCursor()) }.orEmpty()}"

/**
 * 相手が辿るだけの値なので、読める形にしておく必要は無い。
 * 区切りは `_`。`publicId` は UUID なので混ざらない
 */
private fun NotePosition.encodeCursor(): String = "${publishedAt.epochSecond}_${publishedAt.nano}_${publicId.value}"

/**
 * 読めない形なら null。壊れた cursor は先頭に倒す。
 * 相手に教えても直しようが無いので、拒否はしない
 */
private fun decodeCursor(raw: String): NotePosition? {
    val parts = raw.split('_', limit = 3)
    if (parts.size != 3) return null

    val epochSecond = parts[0].toLongOrNull() ?: return null
    val nano = parts[1].toLongOrNull() ?: return null
    if (parts[2].isEmpty()) return null

    return runCatching {
        NotePosition(publishedAt = Instant.ofEpochSecond(epochSecond, nano), publicId = PublicNoteId(parts[2]))
    }.getOrNull()
}

/**
 * 保存した投稿を返す形に直す。
 *
 * @param embedded `Create` に包む場合は `@context` を入れない。外側が持っているので、
 *   重ねると同じものを 2 回書くことになる
 */
private fun noteDocument(
    urls: ActorUrls,
    note: StoredNote,
    embedded: Boolean,
    webPages: WebPageUrls?,
): Note {
    val noteUrls = NoteUrls(domain = urls.domain, publicId = note.publicId)

    return Note(
        context = if (embedded) null else ActivityStreamsIri.DEFAULT_CONTEXT,
        id = noteUrls.noteId,
        attributedTo = urls.actorId,
        content = note.contentHtml,
        published = note.publishedAt.toActivityPubPublished(),
        to = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE),
        cc = listOf(urls.followers),
        atomUri = noteUrls.noteUrl,
        url = webPages?.note(username = urls.username, publicId = note.publicId),
    )
}
