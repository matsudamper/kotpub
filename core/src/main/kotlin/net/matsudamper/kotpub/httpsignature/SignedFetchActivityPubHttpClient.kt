package net.matsudamper.kotpub.httpsignature

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import net.matsudamper.kotpub.actor.InstanceActor
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse
import net.matsudamper.kotpub.http.HttpUrl
import net.matsudamper.kotpub.http.SingleHopActivityPubHttpClient
import net.matsudamper.kotpub.http.SingleHopGetResponse

/**
 * GET に HTTP Signatures を付けて [client] に渡す。
 *
 * Mastodon の AUTHORIZED_FETCH のように、署名の無い GET には投稿もアクターも 401 で返すサーバーがある。
 * 署名しないと、そのサーバーの投稿のブーストや、そこのアカウントからの配送の公開鍵が取れない。
 *
 * サーバー自身のアクター（[InstanceActor]）で署名する。POST は署名済みで渡ってくるのでそのまま通す。
 *
 * リダイレクトはここで追い、移り先ごとに署名し直す。[client] に追わせると、最初の URL の署名のまま
 * 移り先へ送られ、`(request-target)` が食い違って拒まれる
 *
 * @param client 閉じるのはこのクラスの [close] が受け持つ
 */
class SignedFetchActivityPubHttpClient(
    private val client: SingleHopActivityPubHttpClient,
    private val signingActor: InstanceActor,
    private val signer: HttpSignatureSigner,
) : ActivityPubHttpClient {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        readsBody: (status: Int, finalUrl: String) -> Boolean,
    ): ActivityPubClientResponse {
        var currentUrl = url
        repeat(MAX_REDIRECTS + 1) {
            val response = client.getSingleHop(
                url = currentUrl,
                headers = signedHeaders(currentUrl, headers),
                maxBodyBytes = maxBodyBytes,
                readsBody = readsBody,
            )
            when (response) {
                is SingleHopGetResponse.Final -> return response.response
                is SingleHopGetResponse.Redirect -> currentUrl = resolveLocation(currentUrl, response.location)
            }
        }
        throw IOException("リダイレクトが多すぎる: $url")
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): ActivityPubPostResponse = client.post(url = url, headers = headers, body = body)

    override fun close() {
        client.close()
    }

    private fun signedHeaders(
        url: String,
        headers: Map<String, String>,
    ): Map<String, String> {
        val parsedUrl = HttpUrl.parse(url) ?: return headers
        val signedHeaders = signer.sign(
            keyId = signingActor.urls.publicKeyId,
            privateKey = signingActor.privateKey.privateKey,
            method = "GET",
            requestTarget = SignedRequestLine.requestTarget(parsedUrl),
            host = SignedRequestLine.hostHeader(parsedUrl),
            body = ByteArray(0),
        )
        // 大文字小文字だけ違う同名のヘッダが残ると、署名していない値も一緒に送られて検証が落ちる
        val unsignedHeaders = headers.filterKeys { name -> signedHeaders.keys.none { it.equals(name, ignoreCase = true) } }
        return unsignedHeaders + signedHeaders
    }

    /**
     * `Location` は相対 URL のことがあるので、今の URL を基準に解決する。
     *
     * [URI.resolve] は RFC 2396 のままで、`?page=2` のようなクエリだけの参照ではパスの末尾を落とす。
     * RFC 3986 ではパスを保つので、その場合だけ自分で組み立てる
     */
    private fun resolveLocation(
        currentUrl: String,
        location: String,
    ): String =
        try {
            val base = URI(currentUrl)
            if (location.startsWith("?")) {
                // URI の複数引数のコンストラクタは % をもう一度エスケープするので、raw の値を繋ぐ
                "${base.scheme}://${base.rawAuthority}${base.rawPath}$location"
            } else {
                base.resolve(location).toString()
            }
        } catch (exception: IllegalArgumentException) {
            throw IOException("リダイレクト先の URL を読めない: $location", exception)
        } catch (exception: URISyntaxException) {
            throw IOException("リダイレクト先の URL を読めない: $location", exception)
        }

    private companion object {
        // OkHttp や Ktor が自分で追うときの上限に合わせる
        const val MAX_REDIRECTS = 20
    }
}
