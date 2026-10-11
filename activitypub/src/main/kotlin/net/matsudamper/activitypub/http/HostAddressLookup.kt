package net.matsudamper.activitypub.http

import java.net.InetAddress

/**
 * ホスト名を、接続に使うアドレスへ解決する。
 *
 * 検査したあと名前で引き直すと、2 回目だけ内部アドレスを返す DNS リバインディングで
 * 検査をすり抜けられる。引いた [InetAddress] をそのまま接続に渡す
 */
internal fun interface HostAddressLookup {
    fun lookup(host: String): List<InetAddress>
}

internal object SystemHostAddressLookup : HostAddressLookup {
    override fun lookup(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}
