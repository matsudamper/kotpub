package net.matsudamper.activitypub.inbox

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode

/**
 * inbox は処理の成否に関わらず 202 を返すので、届いたものがどう扱われたかは応答からは分からない。
 * 追えるよう、リクエストの span に載せる。
 */
internal object InboxSpan {
    val RECIPIENT: AttributeKey<String> = AttributeKey.stringKey("activitypub.inbox.recipient")
    val SIGNER: AttributeKey<String> = AttributeKey.stringKey("activitypub.inbox.signer")
    val SIGNATURE_REJECT_REASON: AttributeKey<String> = AttributeKey.stringKey("activitypub.inbox.signature_reject_reason")
    val RESULT: AttributeKey<String> = AttributeKey.stringKey("activitypub.inbox.result")
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("activitypub.inbox.outcome")
    val ACTIVITY_TYPE: AttributeKey<String> = AttributeKey.stringKey("activitypub.activity.type")
    val ACTIVITY_ID: AttributeKey<String> = AttributeKey.stringKey("activitypub.activity.id")
    val ACTIVITY_OBJECT: AttributeKey<String> = AttributeKey.stringKey("activitypub.activity.object")
    val STAMP_EMOJI: AttributeKey<String> = AttributeKey.stringKey("activitypub.stamp.emoji")

    fun set(
        key: AttributeKey<String>,
        value: String?,
    ) {
        if (value != null) Span.current().setAttribute(key, value)
    }

    fun outcome(value: String) {
        set(OUTCOME, value)
    }

    fun failed(failure: Throwable) {
        val span = Span.current()
        span.recordException(failure)
        span.setStatus(StatusCode.ERROR)
    }
}
