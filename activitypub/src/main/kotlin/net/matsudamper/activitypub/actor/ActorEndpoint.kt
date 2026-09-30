package net.matsudamper.activitypub.actor

import net.matsudamper.activitypub.activitypub.ActivityPubContentTypes
import net.matsudamper.activitypub.activitypub.Actor
import net.matsudamper.activitypub.activitypub.ActorAttachment
import net.matsudamper.activitypub.activitypub.ActorPublicKey
import net.matsudamper.activitypub.http.EndpointResponse
import net.matsudamper.activitypub.http.HttpStatusCodes
import net.matsudamper.activitypub.url.WebPageUrls

/**
 * Actor エンドポイント。WebFinger から辿り着く 2 ホップ目。
 *
 * 引き当ては [ActorDirectory] に任せる。知らない名前は 404。
 */
class ActorEndpoint(
    private val directory: ActorDirectory,
    private val actorKey: ActorKey,
    private val appearances: StoredActorAppearances,
    private val profiles: StoredActorProfiles,
    private val webPages: WebPageUrls?,
) {
    /**
     * `/users/{username}`
     *
     * @param accept `Accept` ヘッダ。見ずに application/json で返すとアクターとして認識されない
     */
    suspend fun get(
        username: String?,
        accept: String?,
    ): EndpointResponse {
        val urls = directory.resolve(username)
            ?: return EndpointResponse.text(
                status = HttpStatusCodes.NOT_FOUND,
                text = "アクターが見つからない: $username",
                headers = mapOf(),
            )

        return EndpointResponse.json(
            serializer = Actor.serializer(),
            value = actorDocument(
                urls = urls,
                actorKey = actorKey,
                appearance = appearances.find(urls.username),
                profile = profiles.find(urls.username),
                webPages = webPages,
            ),
            contentType = ActivityPubContentTypes.negotiate(accept),
        )
    }
}

/**
 * Actor JSON を組み立てる。
 *
 * 表示名が無ければ名前を出す。説明文が無ければ `summary` を出さない。
 */
internal fun actorDocument(
    urls: ActorUrls,
    actorKey: ActorKey,
    appearance: ActorAppearance,
    profile: ActorProfile,
    webPages: WebPageUrls?,
): Actor {
    val summary = profile.summary?.let { summaryHtml(it) }

    return Actor(
        id = urls.actorId,
        preferredUsername = urls.username,
        name = profile.displayName ?: urls.username,
        summary = summary,
        inbox = urls.inbox,
        endpoints = Actor.Endpoints(sharedInbox = urls.sharedInbox),
        outbox = urls.outbox,
        featured = urls.featured,
        followers = urls.followers,
        following = urls.following,
        url = webPages?.profile(urls.username),
        attachment = linkAttachments(appearance.links),
        icon = appearance.iconVersion?.let { Actor.Image(url = urls.icon(it)) },
        image = appearance.headerVersion?.let { Actor.Image(url = urls.header(it)) },
        showFeatured = false,
        publicKey =
        ActorPublicKey(
            id = urls.publicKeyId,
            owner = urls.actorId,
            publicKeyPem = actorKey.publicKeyPem,
        ),
    )
}

/**
 * `http` と `https` 以外は落とす。リンク先は使う側が外から受け取った文字列のことがあり、
 * `javascript:` のようなものをそのまま相手のプロフィールに載せない
 */
private fun linkAttachments(links: List<ActorLink>): List<ActorAttachment> =
    links
        // scheme は大文字小文字を区別しない（RFC 3986）
        .filter { it.url.startsWith("https://", ignoreCase = true) || it.url.startsWith("http://", ignoreCase = true) }
        .map { linkAttachment(name = it.name, url = it.url) }

private fun linkAttachment(
    name: String,
    url: String,
): ActorAttachment {
    val escaped = escapeHtml(url)
    // rel は Mastodon 側でも付け直されるが、そのまま表示する実装もあるので入れておく
    return ActorAttachment(
        name = name,
        htmlContent = """<a href="$escaped" rel="nofollow noopener" target="_blank">$escaped</a>""",
    )
}

/**
 * 説明文のプレーンテキストを `summary` に入れる HTML にする。
 *
 * 空行で段落に分け、行の切れ目は `<br>` にする。Mastodon が許可するのは
 * この程度のタグで、それ以外は相手側で落とされる。
 */
private fun summaryHtml(text: String): String = text
    .replace("\r\n", "\n")
    .split(Regex("\n{2,}"))
    .filter { it.isNotBlank() }
    .joinToString("") { paragraph ->
        val escaped = paragraph.trim().split("\n").joinToString("<br>") { escapeHtml(it) }
        "<p>$escaped</p>"
    }

private fun escapeHtml(raw: String): String =
    raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
