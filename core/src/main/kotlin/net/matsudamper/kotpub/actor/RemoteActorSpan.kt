package net.matsudamper.kotpub.actor

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode

/**
 * 取得できなかった理由は [RemoteActors] の結果にも載るが、キャッシュを使ったか、取得し直しの間隔で止めたかは載らない。
 * どこで落ちたかを取得の span に載せる。
 */
internal object RemoteActorSpan {
    val URL: AttributeKey<String> = AttributeKey.stringKey("activitypub.remote_actor.url")
    val USE_CACHE: AttributeKey<Boolean> = AttributeKey.booleanKey("activitypub.remote_actor.use_cache")
    val RESULT: AttributeKey<String> = AttributeKey.stringKey("activitypub.remote_actor.result")
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("activitypub.remote_actor.outcome")

    fun outcome(value: String) {
        Span.current().setAttribute(OUTCOME, value)
    }

    fun failed(failure: Throwable) {
        val span = Span.current()
        span.recordException(failure)
        span.setStatus(StatusCode.ERROR)
    }
}
