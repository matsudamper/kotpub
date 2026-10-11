package net.matsudamper.activitypub.http

/**
 * 公開アドレスではない、または http / https ではない宛先への接続を拒んだ。
 *
 * 送り直しても結果は変わらない。配信では失敗として捨てる
 */
class OutboundAddressRejectedException(
    message: String,
) : RuntimeException(message)
