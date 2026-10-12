package net.matsudamper.activitypub.webfinger

import net.matsudamper.activitypub.activitypub.ActivityPubContentTypes
import net.matsudamper.activitypub.actor.ActorDirectory
import net.matsudamper.activitypub.http.EndpointResponse
import net.matsudamper.activitypub.http.HttpStatusCodes

/**
 * WebFinger (RFC 7033) のエンドポイント。アカウント発見の 1 ホップ目。
 *
 * `resource` は必須のクエリパラメータなので、無ければ 400。
 * 知らない相手なら 404 で、ここで 200 を返してしまうと Mastodon 側に
 * 空のアカウントがあるように見える。
 */
class WebFingerEndpoint(
    private val directory: ActorDirectory,
) {
    /**
     * `/.well-known/webfinger`
     *
     * @param resource クエリの `resource`
     */
    suspend fun get(resource: String?): EndpointResponse {
        if (resource.isNullOrBlank()) {
            return EndpointResponse.text(
                status = HttpStatusCodes.BAD_REQUEST,
                text = "resource クエリパラメータが必要（例: ?resource=acct:admin@example.com）",
                headers = mapOf(),
            )
        }

        val urls = directory.resolveResource(resource)
            ?: return EndpointResponse.text(
                status = HttpStatusCodes.NOT_FOUND,
                text = "該当する resource が無い: $resource",
                headers = mapOf(),
            )

        return EndpointResponse.json(
            serializer = WebFingerResponse.serializer(),
            value =
            WebFingerResponse(
                // 要求された綴りではなく正規の acct を返す。
                // 大文字小文字が混ざったまま返すと相手側の突き合わせで揺れる
                subject = urls.acct,
                aliases = listOf(urls.actorId),
                links =
                listOf(
                    WebFingerLink(
                        rel = WebFingerLink.REL_SELF,
                        type = ActivityPubContentTypes.ActivityJson.toString(),
                        href = urls.actorId,
                    ),
                ),
            ),
            contentType = ActivityPubContentTypes.JrdJson,
        )
    }
}
