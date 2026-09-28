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
    private val feedLinks: StoredFeedLinks,
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
                feedLinks = feedLinks.find(urls.username),
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
 * 表示名と説明文は管理画面から設定できる。設定していなければ名前から決まる。
 */
internal fun actorDocument(
    urls: ActorUrls,
    actorKey: ActorKey,
    feedLinks: FeedLinks,
    profile: ActorProfile,
    webPages: WebPageUrls?,
): Actor {
    val storedSummary = profile.summary
    val summary = if (storedSummary == null) SUMMARY else summaryHtml(storedSummary)

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
        attachment = feedAttachments(feedLinks),
        icon = feedLinks.iconUrl?.let { Actor.Image(url = urls.icon(it)) },
        image = feedLinks.headerVersion?.let { Actor.Image(url = urls.header(it)) },
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
 * フィードの URL をプロフィールのリンク集にする。
 *
 * フィードを持たないアカウントは空になる。空の項目を出すと、Mastodon の
 * プロフィールに見出しだけの行が並ぶ。
 */
private fun feedAttachments(feedLinks: FeedLinks): List<ActorAttachment> =
    buildList {
        val siteUrl = feedLinks.siteUrl
        if (siteUrl != null) add(linkAttachment(name = SITE_ATTACHMENT_NAME, url = siteUrl))

        val feedUrl = feedLinks.feedUrl
        if (feedUrl != null) add(linkAttachment(name = FEED_ATTACHMENT_NAME, url = feedUrl))
    }

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

private const val SUMMARY = "RSS/Atom フィードを ActivityPub で配信するアカウント"
private const val SITE_ATTACHMENT_NAME = "サイト"
private const val FEED_ATTACHMENT_NAME = "フィード"
