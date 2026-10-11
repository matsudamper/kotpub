package net.matsudamper.activitypub.actor

import java.io.Closeable
import kotlinx.coroutines.withContext
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import net.matsudamper.activitypub.activitypub.ActivityPubContentTypes
import net.matsudamper.activitypub.crypto.RsaKeys
import net.matsudamper.activitypub.http.ActivityPubHttpClient
import net.matsudamper.activitypub.http.HttpStatusCodes
import net.matsudamper.activitypub.http.HttpUrl
import net.matsudamper.activitypub.httpsignature.PublicKeyLookup
import net.matsudamper.activitypub.httpsignature.SignatureKey
import net.matsudamper.activitypub.json.AppJson

/**
 * 相手のアクター文書を実際に GET して、公開鍵と inbox を取る。
 *
 * ActivityPub では鍵も配信先も「アクター文書の中に入っている」しか配布方法が無いので、
 * 知らない相手から署名付きのリクエストが来たら、その場で相手のサーバーに取りに行くことになる。
 *
 * 取得先は相手が `keyId` や `actor` として指定してきた URL で、こちらが選べない。
 * つまり任意の URL に GET させられる口でもあるため、次を守る。
 *
 * - https のみ。平文で取った鍵は途中で差し替えられる
 * - リダイレクトで別ホストに移ったら捨てる。移った先のサーバーが
 *   他人のアクターの鍵を名乗れてしまう
 * - 文書に書かれた `owner` と `inbox` は、取得先と同じホストであること。
 *   他所のホストのものだと言い張るものを信じない
 * - 大きすぎる応答は読まない
 *
 * 取得結果は [ExpiringCache] を通してキャッシュし、TTL の間は GET しない。
 * フォローや投稿のたびに毎回相手のサーバーへ取りに行くのは、相手に対しても
 * 自分の inbox 処理に対しても無駄が大きいため。取得に失敗した場合はキャッシュしない。
 * 相手のサーバーが一時的に落ちているだけなら、次の呼び出しで取り直せるようにする。
 *
 * 覚えている鍵が古くなったかどうかは期限では分からないので、期限は鍵と関係なく決める。
 * 古い鍵に気付けるのは署名の検証が失敗したときだけで、そこからの取り直しは [refresh]。
 *
 * @param client 閉じるのはこのクラスの [close] が受け持つ
 * @param openTelemetry 取得ごとの span を記録する先。null なら記録しない
 */
class HttpRemoteActors(
    private val client: ActivityPubHttpClient,
    openTelemetry: OpenTelemetry?,
) : RemoteActors,
    Closeable {
    private val tracer = (openTelemetry ?: OpenTelemetry.noop()).getTracer("activitypub-remote-actor")

    /**
     * アクター文書のキャッシュ。鍵と inbox を別々に持たないのは、
     * どちらも同じ 1 つの文書から読むものだから。
     */
    private val documents: ExpiringCache<String, RemoteActorDocument> = createExpiringCache(MAX_CACHED_ACTORS)

    /**
     * 直前に相手のサーバーへ取りに行ったアクター。[refresh] の間隔を空けるのに使う
     */
    private val recentlyFetched: ExpiringCache<String, Unit> = createExpiringCache(MAX_CACHED_ACTORS)

    override suspend fun find(keyId: String): PublicKeyLookup {
        val url = parseHttpsUrl(keyId) ?: return PublicKeyLookup.Unavailable

        return publicKeyOf(keyId, url, fetch(rawUrl = keyId, requestUrl = url, useCache = true))
    }

    /**
     * 覚えているものを使わずに引き直す。
     *
     * 覚えている文書を先に捨てないのは、取り直しに失敗したときに元の文書まで
     * 失うため。通らない署名を 1 通投げ込むだけで、相手のサーバーが落ちている間
     * その相手の正当なアクティビティを全部落とせる状態になる。取り直せなければ
     * 覚えている文書がそのまま返り、[find] と同じ結果になる。
     */
    override suspend fun refresh(keyId: String): PublicKeyLookup {
        val url = parseHttpsUrl(keyId) ?: return PublicKeyLookup.Unavailable

        return publicKeyOf(keyId, url, fetch(rawUrl = keyId, requestUrl = url, useCache = false))
    }

    /**
     * 取れた文書から検証に使う鍵を読む。
     *
     * @param requestUrl 取得先。文書が名乗る鍵の持ち主が、取得先と同じホストか確かめる
     */
    private fun publicKeyOf(
        keyId: String,
        requestUrl: HttpUrl,
        fetched: DocumentFetch,
    ): PublicKeyLookup {
        val document =
            when (fetched) {
                is DocumentFetch.Found -> fetched.document

                // 相手が「もう無い」と答えた場合だけ、消えたものとして返す。
                // 落ちているだけの相手を消えた扱いにすると、後から生き返る
                DocumentFetch.Gone -> return PublicKeyLookup.Gone

                DocumentFetch.Unavailable -> return PublicKeyLookup.Unavailable
            }

        val publicKey = document.publicKey ?: return PublicKeyLookup.Unavailable

        val keyOwnerActorId = publicKey.owner ?: document.id ?: return PublicKeyLookup.Unavailable
        if (!isSameHost(keyOwnerActorId, requestUrl)) return PublicKeyLookup.Unavailable

        val decodedPublicKey =
            runCatching { RsaKeys.decodePublicKeyPem(publicKey.publicKeyPem) }
                .getOrNull() ?: return PublicKeyLookup.Unavailable

        return PublicKeyLookup.Found(
            SignatureKey(keyId = keyId, owner = keyOwnerActorId, publicKey = decodedPublicKey),
        )
    }

    /**
     * 覚えているものを使わない。
     *
     * ここで読む inbox は `Accept` の宛先と以後の配信先として記録に残る。
     * 相手が引っ越した後の古い値を書くと、こちらから届かなくなったことに
     * 気付く手がかりが無い。鍵と違って、間違いが後から直る経路が無い。
     *
     * 実際には署名の検証が直前に同じ文書を取っているので、取りに行く回数は増えない。
     * その場合に使うのはそのとき読んだ文書で、古くても取り直しの間隔のぶんだけ
     */
    override suspend fun findActor(actorId: String): RemoteActor? {
        val url = parseHttpsUrl(actorId) ?: return null
        val document = (fetch(rawUrl = actorId, requestUrl = url, useCache = false) as? DocumentFetch.Found)
            ?.document
            ?: return null

        // 宛先はこちらが POST しに行く先になる。アクターと同じホストに限ることで、
        // 相手が自分の文書に書いた URL でこちらから他所へ POST させる形を塞ぐ
        val inbox = document.inbox?.takeIf { isDeliverable(it, url) } ?: return null

        val publicKeyPem = document.publicKey?.publicKeyPem ?: return null

        return RemoteActor(
            actorId = actorId,
            inbox = inbox,
            // 無いのが普通なので、条件を満たさないものは落として先へ進む。
            // sharedInbox が無くても inbox に 1 通ずつ送れば配信自体はできる
            sharedInbox = document.endpoints?.sharedInbox?.takeIf { isDeliverable(it, url) },
            publicKeyPem = publicKeyPem,
            profile = document.profile(),
        )
    }

    /**
     * POST しに行ってよい宛先か。https で、取得先と同じホストであること
     */
    private fun isDeliverable(
        raw: String,
        actorUrl: HttpUrl,
    ): Boolean = parseHttpsUrl(raw) != null && isSameHost(raw, actorUrl)

    override fun close() {
        client.close()
    }

    /**
     * 1 回の inbox の処理で、署名の検証と宛先の引き当てのために何度か取りに行く。
     * どの取得がキャッシュで済み、どれが何で取れなかったかを取得ごとに分けて残す
     */
    private suspend fun fetch(
        rawUrl: String,
        requestUrl: HttpUrl,
        useCache: Boolean,
    ): DocumentFetch {
        val span =
            tracer.spanBuilder("HttpRemoteActors.fetch")
                .setAttribute(RemoteActorSpan.URL, rawUrl)
                .setAttribute(RemoteActorSpan.USE_CACHE, useCache)
                .startSpan()
        return try {
            withContext(Context.current().with(span).asContextElement()) {
                fetchDocument(rawUrl = rawUrl, requestUrl = requestUrl, useCache = useCache)
            }.also { fetched ->
                span.setAttribute(
                    RemoteActorSpan.RESULT,
                    when (fetched) {
                        is DocumentFetch.Found -> "found"
                        DocumentFetch.Gone -> "gone"
                        DocumentFetch.Unavailable -> "unavailable"
                    },
                )
            }
        } finally {
            span.end()
        }
    }

    /**
     * アクター文書を取る。取得先の URL を [requestUrl] として渡すのは、
     * 取れた文書の中身を突き合わせる基準がその URL のホストだから。
     */
    private suspend fun fetchDocument(
        rawUrl: String,
        requestUrl: HttpUrl,
        useCache: Boolean,
    ): DocumentFetch {
        // `keyId` はアクター id にフラグメントを付けたもので、フラグメントはサーバーに
        // 送られない。落としてから引くと、署名の検証で取った文書を
        // `Accept` の宛先を決めるときにも使える
        val cacheKey = rawUrl.substringBefore('#')

        if (useCache) {
            documents.get(cacheKey)?.let {
                RemoteActorSpan.outcome("cache_hit")
                return DocumentFetch.Found(it)
            }
            // 取りに行くことにしたので、次に取り直すまでの間隔をここから数える
            recentlyFetched.put(key = cacheKey, value = Unit, ttlMillis = REFRESH_INTERVAL_MILLIS)
        } else if (!recentlyFetched.tryPut(key = cacheKey, value = Unit, ttlMillis = REFRESH_INTERVAL_MILLIS)) {
            // 取りに行く枠を取れなかった。覚えているものを使わない呼び出しは、
            // 通らない署名を投げ込むだけで誰でも起こせるので間隔を空ける。
            // 枠は取れたかどうかに関わらず塞ぐ。落ちている相手に繋ぎ直しても同じ結果になる。
            //
            // 直前に取りに行ったばかりなら、覚えているものはそのとき読んだ文書で、
            // 取り直しても同じものにしかならない
            RemoteActorSpan.outcome("throttled")
            return documents.get(cacheKey)?.let { DocumentFetch.Found(it) } ?: DocumentFetch.Unavailable
        }

        val response =
            runCatching {
                client.get(
                    url = rawUrl,
                    headers = mapOf(ACCEPT_HEADER to ActivityPubContentTypes.ActivityJson.toString()),
                    maxBodyBytes = MAX_BODY_BYTES,
                    // 下で status とホストだけで捨てる応答は本文を読まない
                    readsBody = { status, finalUrl ->
                        HttpStatusCodes.isSuccess(status) && HttpUrl.parse(finalUrl)?.isSameHost(requestUrl) == true
                    },
                )
            }.getOrElse { failure ->
                RemoteActorSpan.failed(failure)
                RemoteActorSpan.outcome("request_failed")
                return DocumentFetch.Unavailable
            }

        // リダイレクトを追った結果、別のホストに移っていたら信用しない。
        // status を見る前に確かめるのは、消えたかどうかを答えてよいのは
        // そのアクターのホストだけだから
        val fetchedFrom = HttpUrl.parse(response.finalUrl)
        if (fetchedFrom == null || !fetchedFrom.isSameHost(requestUrl)) {
            RemoteActorSpan.outcome("redirected_to_other_host")
            return DocumentFetch.Unavailable
        }

        // 消えたと見なすのは 410 だけ。Mastodon は削除済みのアカウントにこれを返す。
        // 404 は消したのか置き場所が変わったのかを区別できず、
        // 一時的なルーティングの不調でも返るので、分からないものとして扱う
        if (response.status == HttpStatusCodes.GONE) {
            // 消えた相手の文書を覚えたままにしない。取り直しの間隔の中に来た次の
            // 呼び出しが覚えているものを使い、消えたアクターを生きているものとして扱う
            documents.invalidate(cacheKey)
            RemoteActorSpan.outcome("gone")
            return DocumentFetch.Gone
        }
        if (!HttpStatusCodes.isSuccess(response.status)) {
            RemoteActorSpan.outcome("unsuccessful_status")
            return DocumentFetch.Unavailable
        }

        // 本文は status を見た後で読む。読めなかった理由は例外として span に残す
        val body =
            checkNotNull(response.body) { "成功応答なのに本文を読んでいない: $rawUrl" }.getOrElse { failure ->
                RemoteActorSpan.failed(failure)
                RemoteActorSpan.outcome("request_failed")
                return DocumentFetch.Unavailable
            }
        if (body.size > MAX_BODY_BYTES) {
            RemoteActorSpan.outcome("body_too_large")
            return DocumentFetch.Unavailable
        }

        val document =
            runCatching {
                AppJson.decodeFromString(RemoteActorDocument.serializer(), body.toString(Charsets.UTF_8))
            }.getOrNull()
        if (document == null) {
            RemoteActorSpan.outcome("undecodable_document")
            return DocumentFetch.Unavailable
        }

        // 使えると分かった文書だけを覚える。中身を見ずに入れ替えると、相手が 200 で
        // 空の JSON や他所のアクターの鍵を返した瞬間に、それまでの正しい文書を失う。
        // 取り直しは通らない署名を投げ込むだけで起こせるので、外から狙える
        if (!document.hasUsableKey(requestUrl)) {
            RemoteActorSpan.outcome("no_usable_key")
            return documents.get(cacheKey)?.let { DocumentFetch.Found(it) } ?: DocumentFetch.Found(document)
        }

        documents.put(key = cacheKey, value = document, ttlMillis = CACHE_TTL_MILLIS)
        RemoteActorSpan.outcome("fetched")
        return DocumentFetch.Found(document)
    }

    /**
     * 覚える値打ちがあるか。検証に使える鍵が、取得先と同じホストの持ち主で入っていること。
     *
     * [publicKeyOf] と同じ条件で見る。読めない鍵の文書を覚えると、そこから先の
     * 検証は鍵が無いものとして落ち、[refresh] にも進まないまま期限まで拒み続ける。
     *
     * ここを通らない文書でも、呼び出し側は結果として受け取る。そこで落ちるのは
     * 同じ判断で、覚えるかどうかだけをここで決める
     */
    private fun RemoteActorDocument.hasUsableKey(requestUrl: HttpUrl): Boolean {
        val publicKey = publicKey ?: return false

        val keyOwnerActorId = publicKey.owner ?: id ?: return false
        if (!isSameHost(keyOwnerActorId, requestUrl)) return false

        return runCatching { RsaKeys.decodePublicKeyPem(publicKey.publicKeyPem) }.isSuccess
    }

    /**
     * [fetch] の結果。取れなかった理由のうち「もう無い」だけは区別する
     */
    private sealed interface DocumentFetch {
        data class Found(
            val document: RemoteActorDocument,
        ) : DocumentFetch

        data object Gone : DocumentFetch

        data object Unavailable : DocumentFetch
    }

    private fun parseHttpsUrl(raw: String): HttpUrl? = HttpUrl.parseHttps(raw)

    private fun isSameHost(
        raw: String,
        expected: HttpUrl,
    ): Boolean {
        val url = HttpUrl.parse(raw) ?: return false
        return url.isSameHost(expected)
    }

    private companion object {
        const val ACCEPT_HEADER = "Accept"

        /**
         * 読み込む応答の上限。アクター文書は鍵を含めても数 KB にしかならない。
         */
        const val MAX_BODY_BYTES = 64 * 1024

        /**
         * キャッシュの有効期間。
         *
         * 期限を短くしても鍵の入れ替わりには追い付けない。替わったことは通らない
         * 署名が届いて初めて分かるもので、それは [refresh] が受け持つ。ここで決まるのは
         * 相手のサーバーを引きに行く頻度だけなので、長く持つ。
         * Mastodon が相手のアカウントを古いと見なす間隔（`STALE_THRESHOLD`）に合わせて 1 日
         */
        const val CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L

        /**
         * 同じアクターを取り直すまでに空ける間隔。
         *
         * 相手が鍵を替えてから、こちらが受け取れるようになるまでの遅れの上限になる。
         * 相手のサーバーは送り直してくるので、短くする意味はこの遅れを縮めることだけ。
         * Mastodon が取得の失敗後に空ける間隔（`STOPLIGHT_COOL_OFF_TIME`）に合わせて 5 分
         */
        const val REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000L

        /**
         * 覚えておくアクターの数の上限。
         *
         * キーは相手が名乗る `keyId` で、使い捨てのものをいくらでも送り込める。
         * 期限だけに任せると、送られた分がその期限のあいだ残る。溢れた分を捨てても
         * 次に要るときに取り直すだけで、判断は変わらない
         */
        const val MAX_CACHED_ACTORS = 10_000
    }
}
