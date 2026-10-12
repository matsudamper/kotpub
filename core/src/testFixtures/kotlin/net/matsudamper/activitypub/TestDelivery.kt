package net.matsudamper.activitypub

import net.matsudamper.activitypub.actor.ActorUrls
import net.matsudamper.activitypub.delivery.ActivityDelivery
import net.matsudamper.activitypub.delivery.DeliveryResult

/**
 * 送信の差し替え。送ったつもりのものを溜めておく。
 *
 * ネットワークに出る唯一の口なので、テストからは必ずこちらを通す。
 * 実際に POST するところまで含めた確認は `HttpActivityDeliveryTest` で行う。
 */
class TestDelivery(
    private val result: DeliveryResult = DeliveryResult.Delivered,
) : ActivityDelivery {
    val delivered: MutableList<Delivered> = mutableListOf()

    override suspend fun deliver(
        inbox: String,
        sender: ActorUrls,
        body: ByteArray,
    ): DeliveryResult {
        delivered += Delivered(inbox = inbox, sender = sender, body = body.decodeToString())
        return result
    }

    override fun close() {
    }

    data class Delivered(
        val inbox: String,
        val sender: ActorUrls,
        val body: String,
    )
}
