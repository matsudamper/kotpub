package net.matsudamper.kotpub.http

import java.net.UnknownHostException

/**
 * 名前解決の結果に、公開アドレスではないものが含まれていた。
 *
 * 名前解決に失敗したときと同じく接続の前に失敗させるので、[UnknownHostException] として投げる。
 * 送り直しても結果は変わらない
 */
class NonPublicAddressException(
    message: String,
) : UnknownHostException(message)
