package net.matsudamper.kotpub.httpsignature

import net.matsudamper.kotpub.actor.LocalActor
import net.matsudamper.kotpub.http.ActivityPubClientResponse
import net.matsudamper.kotpub.http.ActivityPubHttpClient
import net.matsudamper.kotpub.http.ActivityPubPostResponse
import net.matsudamper.kotpub.http.HttpUrl

/**
 * GET に HTTP Signatures を付けて [client] に渡す。
 *
 * Mastodon の AUTHORIZED_FETCH のように、署名の無い GET には投稿もアクターも 401 で返すサーバーがある。
 * 署名しないと、そのサーバーの投稿のブーストや、そこのアカウントからの配送の公開鍵が取れない。
 *
 * 署名に使うアクターが居なければ署名せずに送る。POST は署名済みで渡ってくるのでそのまま通す
 *
 * @param client 閉じるのはこのクラスの [close] が受け持つ
 */
class SignedFetchActivityPubHttpClient(
    private val client: ActivityPubHttpClient,
    private val signingActor: SignedFetchActor,
    private val signer: HttpSignatureSigner,
) : ActivityPubHttpClient {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        readsBody: (status: Int, finalUrl: String) -> Boolean,
    ): ActivityPubClientResponse {
        val parsedUrl = HttpUrl.parse(url)
        val actor = signingActor.find()
        if (parsedUrl == null || actor == null) {
            return client.get(url = url, headers = headers, maxBodyBytes = maxBodyBytes, readsBody = readsBody)
        }
        val signedHeaders = signer.sign(
            keyId = actor.urls.publicKeyId,
            privateKey = actor.privateKey.privateKey,
            method = "GET",
            requestTarget = SignedRequestLine.requestTarget(parsedUrl),
            host = SignedRequestLine.hostHeader(parsedUrl),
            body = ByteArray(0),
        )
        // 大文字小文字だけ違う同名のヘッダが残ると、署名していない値も一緒に送られて検証が落ちる
        val unsignedHeaders = headers.filterKeys { name -> signedHeaders.keys.none { it.equals(name, ignoreCase = true) } }
        return client.get(url = url, headers = unsignedHeaders + signedHeaders, maxBodyBytes = maxBodyBytes, readsBody = readsBody)
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): ActivityPubPostResponse = client.post(url = url, headers = headers, body = body)

    override fun close() {
        client.close()
    }
}

/**
 * GET の署名に使う、こちらのアクター。
 *
 * 相手はこのアクターの公開鍵を取りに来るので、公開しているアクターであること
 */
fun interface SignedFetchActor {
    /**
     * @return 署名に使えるアクターが居なければ null
     */
    suspend fun find(): LocalActor?
}
