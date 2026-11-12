package net.matsudamper.kotpub.note

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import net.matsudamper.kotpub.activity.ActivityStreamsIri
import net.matsudamper.kotpub.activity.AnnounceActivity
import net.matsudamper.kotpub.activity.BoostActivities
import net.matsudamper.kotpub.activity.CreateNoteActivity
import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.actor.ActorDirectory
import net.matsudamper.kotpub.actor.ActorUrls
import net.matsudamper.kotpub.collection.COLLECTION_CURSOR_PARAM
import net.matsudamper.kotpub.collection.COLLECTION_PAGE_SIZE
import net.matsudamper.kotpub.collection.FEATURED_COLLECTION_SIZE
import net.matsudamper.kotpub.collection.OrderedCollection
import net.matsudamper.kotpub.collection.OrderedCollectionPage
import net.matsudamper.kotpub.collection.OrderedCollectionWithItems
import net.matsudamper.kotpub.entity.PublicNoteId
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.HttpStatusCodes
import net.matsudamper.kotpub.http.QueryParameter
import net.matsudamper.kotpub.json.AppJson
import net.matsudamper.kotpub.url.WebPageUrls

/**
 * 配信した投稿を返す。
 *
 * 相手は受け取った `Create` の `object.id` をパーマリンクとして取得しに来る。
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
    private val boosts: OutboxBoostStore,
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
        val total = notes.count(urls.username) + boosts.count(urls.username)

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

        // 読めない cursor は先頭のページとして扱う。相手が辿るだけの値なので、
        // 壊れていることを教えても直しようが無い
        val after = cursor.ifEmpty { null }?.let { decodeCursor(it) }
        // 投稿とブーストをそれぞれ 1 ページ分取って新しい順に混ぜ、1 ページ分だけ返す。残りは次のページで取り直す
        val noteItems = notes.list(
            username = urls.username,
            after = after?.let { NotePosition(publishedAt = it.at, publicId = PublicNoteId(it.key)) },
            limit = COLLECTION_PAGE_SIZE,
        ).map { note ->
            OutboxItem(
                position = OutboxPosition(at = note.publishedAt, key = note.publicId.value),
                activity = AppJson.encodeToJsonElement(
                    CreateNoteActivity.serializer(),
                    CreateNoteActivity(
                        id = NoteUrls(domain = urls.domain, publicId = note.publicId).createId,
                        actor = urls.actorId,
                        published = note.publishedAt.toActivityPubPublished(),
                        to = listOf(ActivityStreamsIri.PUBLIC_AUDIENCE),
                        cc = note.ccWith(urls.followers),
                        target = noteDocument(urls = urls, note = note, embedded = true, webPages = webPages),
                    ),
                ),
            )
        }
        val boostItems = boosts.list(username = urls.username, after = after, limit = COLLECTION_PAGE_SIZE).map { boost ->
            OutboxItem(
                position = boost.position,
                activity = AppJson.encodeToJsonElement(
                    AnnounceActivity.serializer(),
                    BoostActivities.announce(
                        id = boost.announceId,
                        actor = urls.actorId,
                        objectUri = boost.objectUri,
                        objectAuthor = boost.objectAuthor,
                        followers = urls.followers,
                        published = boost.boostedAt,
                        audience = boost.audience,
                    ),
                ),
            )
        }
        val page = (noteItems + boostItems)
            .sortedWith(compareByDescending<OutboxItem> { it.position.at }.thenByDescending { it.position.key })
            .take(COLLECTION_PAGE_SIZE)

        return EndpointResponse.json(
            serializer = OrderedCollectionPage.serializer(JsonElement.serializer()),
            value = OrderedCollectionPage(
                id = pageUrl(urls, after),
                totalItems = total,
                partOf = urls.outbox,
                orderedItems = page.map { it.activity },
                // 総数ではなく取得できた件数で判断する。読んでいる間に増えていることがある
                next = if (page.size < COLLECTION_PAGE_SIZE) null else pageUrl(urls, page.last().position),
            ),
            contentType = contentType,
        )
    }

    private class OutboxItem(
        val position: OutboxPosition,
        val activity: JsonElement,
    )
}

/**
 * プロフィールに載せる投稿の一覧。Actor の `featured` が指している先。
 *
 * Mastodon はここに並んだ投稿をプロフィールの先頭に固定して出す
 */
class FeaturedEndpoint(
    private val directory: ActorDirectory,
    private val featuredNotes: FeaturedNoteStore,
    private val webPages: WebPageUrls?,
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

        val notes = featuredNotes.featured(username = urls.username, limit = FEATURED_COLLECTION_SIZE)
        return EndpointResponse.json(
            serializer = OrderedCollectionWithItems.serializer(Note.serializer()),
            value = OrderedCollectionWithItems(
                id = urls.featured,
                totalItems = notes.size.toLong(),
                orderedItems = notes.map { note -> noteDocument(urls = urls, note = note, embedded = true, webPages = webPages) },
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
    after: OutboxPosition?,
): String = "${urls.outbox}?$COLLECTION_CURSOR_PARAM=${after?.let { QueryParameter.encode(it.encodeCursor()) }.orEmpty()}"

/**
 * 相手が辿るだけの値なので、読める形にしておく必要は無い。
 * 区切りは `_`。key は最後に置くので、key に `_` が入っていても読める
 */
private fun OutboxPosition.encodeCursor(): String = "${at.epochSecond}_${at.nano}_$key"

/**
 * 読めない形なら null。壊れた cursor は先頭のページとして扱う。
 * 相手に教えても直しようが無いので、拒否はしない
 */
private fun decodeCursor(raw: String): OutboxPosition? {
    val parts = raw.split('_', limit = 3)
    if (parts.size != 3) return null

    val epochSecond = parts[0].toLongOrNull() ?: return null
    val nano = parts[1].toLongOrNull() ?: return null
    if (parts[2].isEmpty()) return null

    return runCatching {
        OutboxPosition(at = Instant.ofEpochSecond(epochSecond, nano), key = parts[2])
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
        cc = note.ccWith(urls.followers),
        atomUri = noteUrls.noteUrl,
        url = webPages?.note(username = urls.username, publicId = note.publicId),
        attachment = note.attachments.toNoteDocuments(),
        inReplyTo = note.inReplyTo,
        tag = note.mentions.toNoteMentionTags(),
    )
}
