package net.matsudamper.activitypub.http

import java.net.Inet4Address
import java.net.InetAddress

/**
 * 接続してよいアドレスか。
 *
 * 名前解決の結果に 1 つでも非公開が混ざっていたら、その名前には接続しない。
 * 混ざった公開アドレスだけに繋ぐと、同じ応答を返した内部向けのアドレスを取りこぼす。
 *
 * IPv4 射影（`::ffff:a.b.c.d`）は `InetAddress` が IPv4 に潰して返す。
 * IPv4 互換形式、NAT64（`64:ff9b::/96`）、6to4（`2002::/16`）は IPv6 のままなので、
 * 中の IPv4 を取り出して同じ基準で見る。外側だけ見ると公開アドレスに見える
 */
internal object PublicInetAddresses {
    fun isPublic(address: InetAddress): Boolean {
        val ipv4 = ipv4Bytes(address)
        if (ipv4 != null) return isPublicIpv4(ipv4)
        return isPublicIpv6(address.address)
    }

    private fun ipv4Bytes(address: InetAddress): ByteArray? {
        if (address is Inet4Address) return address.address
        val bytes = address.address
        if (bytes.size != IPV6_LENGTH) return null
        return embeddedIpv4(bytes)
    }

    private fun embeddedIpv4(bytes: ByteArray): ByteArray? {
        if (isIpv4Mapped(bytes) || isIpv4Compatible(bytes) || isNat64(bytes)) {
            return bytes.copyOfRange(IPV4_EMBEDDED_AT, IPV6_LENGTH)
        }
        if (is6to4(bytes)) return bytes.copyOfRange(SIXTO4_IPV4_AT, SIXTO4_IPV4_AT + IPV4_LENGTH)
        return null
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        bytes.prefixIsZero(MAPPED_PREFIX_LENGTH) &&
            bytes[MAPPED_PREFIX_LENGTH] == 0xff.toByte() &&
            bytes[MAPPED_PREFIX_LENGTH + 1] == 0xff.toByte()

    private fun isIpv4Compatible(bytes: ByteArray): Boolean = bytes.prefixIsZero(IPV4_EMBEDDED_AT)

    private fun isNat64(bytes: ByteArray): Boolean =
        bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x64.toByte() &&
            bytes[2] == 0xff.toByte() &&
            bytes[3] == 0x9b.toByte() &&
            (NAT64_PREFIX_LENGTH until IPV4_EMBEDDED_AT).all { bytes[it] == 0.toByte() }

    private fun is6to4(bytes: ByteArray): Boolean = bytes[0] == 0x20.toByte() && bytes[1] == 0x02.toByte()

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes.u8(0)
        val second = bytes.u8(1)
        if (first == 0) return false
        if (first == LOOPBACK_OCTET) return false
        if (first == RFC1918_10) return false
        if (first == RFC1918_172 && second in RFC1918_172_MIN..RFC1918_172_MAX) return false
        if (first == RFC1918_192 && second == RFC1918_168) return false
        if (first == LINK_LOCAL_169 && second == LINK_LOCAL_254) return false
        if (first == CGNAT_100 && second in CGNAT_MIN..CGNAT_MAX) return false
        if (first in MULTICAST_MIN..MULTICAST_MAX) return false
        if ((0 until IPV4_LENGTH).all { bytes.u8(it) == BROADCAST_OCTET }) return false
        return true
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != IPV6_LENGTH) return false
        if (bytes.all { it == 0.toByte() }) return false
        if (bytes.prefixIsZero(IPV6_LENGTH - 1) && bytes[IPV6_LENGTH - 1] == 1.toByte()) return false
        val high = bytes.u8(0)
        val next = bytes.u8(1)
        if (high and ULA_MASK == ULA_VALUE) return false
        if (high == LINK_LOCAL_V6 && next and LINK_LOCAL_V6_MASK == LINK_LOCAL_V6_VALUE) return false
        if (high == LINK_LOCAL_V6 && next and LINK_LOCAL_V6_MASK == SITE_LOCAL_V6_VALUE) return false
        if (high == MULTICAST_V6) return false
        return true
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

    private fun ByteArray.prefixIsZero(length: Int): Boolean = (0 until length).all { this[it] == 0.toByte() }

    private const val IPV4_LENGTH = 4
    private const val IPV6_LENGTH = 16
    private const val IPV4_EMBEDDED_AT = 12
    private const val MAPPED_PREFIX_LENGTH = 10
    private const val NAT64_PREFIX_LENGTH = 4
    private const val SIXTO4_IPV4_AT = 2

    private const val LOOPBACK_OCTET = 127
    private const val RFC1918_10 = 10
    private const val RFC1918_172 = 172
    private const val RFC1918_172_MIN = 16
    private const val RFC1918_172_MAX = 31
    private const val RFC1918_192 = 192
    private const val RFC1918_168 = 168
    private const val LINK_LOCAL_169 = 169
    private const val LINK_LOCAL_254 = 254
    private const val CGNAT_100 = 100
    private const val CGNAT_MIN = 64
    private const val CGNAT_MAX = 127
    private const val MULTICAST_MIN = 224
    private const val MULTICAST_MAX = 239
    private const val BROADCAST_OCTET = 255

    private const val ULA_MASK = 0xfe
    private const val ULA_VALUE = 0xfc
    private const val LINK_LOCAL_V6 = 0xfe
    private const val LINK_LOCAL_V6_MASK = 0xc0
    private const val LINK_LOCAL_V6_VALUE = 0x80
    private const val SITE_LOCAL_V6_VALUE = 0xc0
    private const val MULTICAST_V6 = 0xff
}
