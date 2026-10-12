package net.matsudamper.activitypub.http

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.readByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.utils.io.readRemaining
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry

/**
 * Ktor の CIO で相手のサーバーと話す [ActivityPubHttpClient]。
 *
 * GET と POST でリダイレクトの扱いが違うので、クライアントを分けて持つ。
 * 使わない側は作らない。
 *
 * @param openTelemetry null でなければ、外向きのリクエストを span として記録する
 */
class KtorActivityPubHttpClient(
    private val openTelemetry: OpenTelemetry?,
) : ActivityPubHttpClient {
    private val getClient = lazy { createClient(followRedirects = true) }
    private val postClient = lazy { createClient(followRedirects = false) }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        maxBodyBytes: Int,
        readsBody: (status: Int, finalUrl: String) -> Boolean,
    ): ActivityPubClientResponse =
        // execute の中で本文を読み、読めなくても status は返す。get だと本文を読み終えるまで
        // status が手に入らず、本文の大きさも確かめられない
        getClient.value
            .prepareGet(url) {
                headers.forEach { (name, value) -> header(name, value) }
            }.execute { response ->
                val status = response.status.value
                val finalUrl = response.request.url.toString()
                ActivityPubClientResponse(
                    status = status,
                    finalUrl = finalUrl,
                    body =
                    if (readsBody(status, finalUrl)) {
                        runCatching { response.bodyAsChannel().readRemaining(maxBodyBytes + 1L).readByteArray() }
                            // 呼び出し元が止めた合図まで本文の失敗に化けさせない
                            .onFailure { if (it is CancellationException) throw it }
                    } else {
                        null
                    },
                )
            }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): ActivityPubPostResponse =
        // execute の中で status だけ読む。ブロックを抜けると本文は読まずに捨てられる
        postClient.value
            .preparePost(url) {
                headers.forEach { (name, value) -> header(name, value) }
                setBody(body)
            }.execute { response -> ActivityPubPostResponse(status = response.status.value) }

    override fun close() {
        if (getClient.isInitialized()) getClient.value.close()
        if (postClient.isInitialized()) postClient.value.close()
    }

    private fun createClient(followRedirects: Boolean): HttpClient =
        HttpClient(CIO) {
            if (openTelemetry != null) {
                install(KtorClientTelemetry) {
                    setOpenTelemetry(openTelemetry)
                }
            }
            // 相手のサーバーが応答しないままだと inbox の処理や配信が詰まる。
            // 1 件のために長く待つ意味は無いので短く切る
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            // status を見て判断するので、4xx や 5xx で例外にしない
            expectSuccess = false
            this.followRedirects = followRedirects
        }
}
