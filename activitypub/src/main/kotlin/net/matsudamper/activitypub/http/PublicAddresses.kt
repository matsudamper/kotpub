package net.matsudamper.activitypub.http

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * インターネット上の相手として繋いでよいアドレスか。
 *
 * IANA の Special-Purpose Address Registry で globally reachable でない範囲と、
 * 中に IPv4 を埋め込んで内部へ届きうる範囲を外す。
 * IPv6 は割り当て済みのグローバルユニキャスト 2000::/3 だけを起点にし、
 * 未知の範囲は通さない
 */
internal object PublicAddresses {
    private val nonPublicIpv4Blocks =
        listOf(
            AddressBlock.of("0.0.0.0", 8),
            AddressBlock.of("10.0.0.0", 8),
            AddressBlock.of("100.64.0.0", 10),
            AddressBlock.of("127.0.0.0", 8),
            AddressBlock.of("169.254.0.0", 16),
            AddressBlock.of("172.16.0.0", 12),
            AddressBlock.of("192.0.0.0", 24),
            AddressBlock.of("192.0.2.0", 24),
            AddressBlock.of("192.88.99.0", 24),
            AddressBlock.of("192.168.0.0", 16),
            AddressBlock.of("198.18.0.0", 15),
            AddressBlock.of("198.51.100.0", 24),
            AddressBlock.of("203.0.113.0", 24),
            // マルチキャスト、予約、ブロードキャスト
            AddressBlock.of("224.0.0.0", 3),
        )

    private val globalUnicastIpv6 = AddressBlock.of("2000::", 3)

    private val nonPublicIpv6BlocksInGlobalUnicast =
        listOf(
            // Teredo などを含む。到達可能とされる細かい割り当てはサーバーの置き場所にならないのでまとめて外す
            AddressBlock.of("2001::", 23),
            AddressBlock.of("2001:db8::", 32),
            // 6to4。中の IPv4 が内部でも、中継を通ってそこへ届きうる
            AddressBlock.of("2002::", 16),
            AddressBlock.of("3fff::", 20),
        )

    /**
     * IPv6 だけの環境では、IPv4 のサーバーへ DNS64 が返すこの範囲で繋ぐ。
     * 中に埋め込まれた IPv4 の宛先へ変換されるので、その IPv4 で判定する
     */
    private val nat64WellKnownPrefix = AddressBlock.of("64:ff9b::", 96)

    fun isPublic(address: InetAddress): Boolean =
        when (address) {
            is Inet4Address -> nonPublicIpv4Blocks.none { it.contains(address.address) }
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (nat64WellKnownPrefix.contains(bytes)) {
            val embeddedIpv4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
            return isPublic(embeddedIpv4)
        }
        if (!globalUnicastIpv6.contains(bytes)) return false

        return nonPublicIpv6BlocksInGlobalUnicast.none { it.contains(bytes) }
    }
}

private class AddressBlock private constructor(
    private val prefix: ByteArray,
    private val prefixLength: Int,
) {
    fun contains(address: ByteArray): Boolean {
        if (address.size != prefix.size) return false

        val fullBytes = prefixLength / Byte.SIZE_BITS
        for (index in 0 until fullBytes) {
            if (address[index] != prefix[index]) return false
        }
        val remainingBits = prefixLength % Byte.SIZE_BITS
        if (remainingBits == 0) return true

        val mask = (0xFF shl (Byte.SIZE_BITS - remainingBits)) and 0xFF
        return (address[fullBytes].toInt() and mask) == (prefix[fullBytes].toInt() and mask)
    }

    companion object {
        /**
         * [literal] は IP アドレスの表記に限る。名前を渡すと名前解決が走る
         */
        fun of(
            literal: String,
            prefixLength: Int,
        ): AddressBlock = AddressBlock(InetAddress.getByName(literal).address, prefixLength)
    }
}
