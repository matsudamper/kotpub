package net.matsudamper.kotpub.webfinger

import net.matsudamper.kotpub.activitypub.ActivityPubContentTypes
import net.matsudamper.kotpub.actor.ActorDirectory
import net.matsudamper.kotpub.actor.InstanceActorUrls
import net.matsudamper.kotpub.http.EndpointResponse
import net.matsudamper.kotpub.http.HttpStatusCodes

/**
 * WebFinger (RFC 7033) のエンドポイント。アカウント発見の 1 ホップ目。
 *
 * `resource` は必須のクエリパラメータなので、無ければ 400。
 * 知らない相手なら 404 で、ここで 200 を返してしまうと Mastodon 側に
 * 空のアカウントがあるように見える。
 *
 * サーバー自身のアクター（[InstanceActorUrls]）も `acct:{domain}@{domain}` で引ける。
 * Mastodon はアクターを取り込むときに WebFinger で名前を確かめるので、引けないと鍵も取り込まれない
 */
class WebFingerEndpoint(
    private val directory: ActorDirectory,
    private val instanceActor: InstanceActorUrls,
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

        // ドメインと同じ名前のアカウントが既にあれば、今までどおりそのアカウントを返す
        val urls = directory.resolveResource(resource)
        if (urls != null) {
            return found(acct = urls.acct, actorId = urls.actorId)
        }

        if (isInstanceActor(resource)) {
            return found(acct = instanceActor.acct, actorId = instanceActor.actorId)
        }

        return EndpointResponse.text(
            status = HttpStatusCodes.NOT_FOUND,
            text = "該当する resource が無い: $resource",
            headers = mapOf(),
        )
    }

    private fun isInstanceActor(resource: String): Boolean {
        val trimmed = resource.trim()
        return trimmed.equals(instanceActor.acct, ignoreCase = true) ||
            trimmed.equals(instanceActor.acct.removePrefix(ACCT_SCHEME), ignoreCase = true) ||
            trimmed.equals(instanceActor.actorId, ignoreCase = true)
    }

    private fun found(
        acct: String,
        actorId: String,
    ): EndpointResponse =
        EndpointResponse.json(
            serializer = WebFingerResponse.serializer(),
            value =
            WebFingerResponse(
                // 要求された文字列ではなく正規の acct を返す。
                // 大文字小文字が混ざったまま返すと相手側の突き合わせで揺れる
                subject = acct,
                aliases = listOf(actorId),
                links =
                listOf(
                    WebFingerLink(
                        rel = WebFingerLink.REL_SELF,
                        type = ActivityPubContentTypes.ActivityJson.toString(),
                        href = actorId,
                    ),
                ),
            ),
            contentType = ActivityPubContentTypes.JrdJson,
        )

    private companion object {
        const val ACCT_SCHEME = "acct:"
    }
}
