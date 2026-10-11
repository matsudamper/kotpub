package net.matsudamper.activitypub.http

import java.net.Inet4Address
import java.net.InetAddress

/**
 * 接続してよいアドレスか。グローバルに到達できるユニキャストだけを許す。
 *
 * 名前解決の結果に 1 つでもそうでないものが混ざっていたら、その名前には接続しない。
 * 混ざった到達可能なアドレスだけに繋ぐと、同じ応答を返した内部向けのアドレスを取りこぼす。
 *
 * IPv4 射影（`::ffff:a.b.c.d`）は `InetAddress` が IPv4 に潰して返す。
 * IPv4 互換形式、NAT64（`64:ff9b::/96`）、6to4（`2002::/16`）は IPv6 のままなので、
 * 中の IPv4 を取り出して同じ基準で見る。外側だけ見ると到達できるように見える
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

    /**
     * プライベート、ループバック、リンクローカル、CGNAT に加え、
     * 文書用、ベンチマーク用、プロトコル割り当て、マルチキャスト、予約（240/4）は到達できない
     */
    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes.u8(0)
        val second = bytes.u8(1)
        val third = bytes.u8(2)
        if (first == 0 || first == 10 || first == LOOPBACK_OCTET) return false
        if (first == CGNAT_100 && second in CGNAT_MIN..CGNAT_MAX) return false
        if (first == LINK_LOCAL_169 && second == LINK_LOCAL_254) return false
        if (first == RFC1918_172 && second in RFC1918_172_MIN..RFC1918_172_MAX) return false
        if (first == RFC1918_192 && second == 0 && (third == 0 || third == 2)) return false
        if (first == RFC1918_192 && second == DEPRECATED_6TO4_ANYCAST && third == DEPRECATED_6TO4_ANYCAST_THIRD) {
            return false
        }
        if (first == RFC1918_192 && second == RFC1918_168) return false
        if (first == BENCHMARK_198 && second in BENCHMARK_MIN..BENCHMARK_MAX) return false
        if (first == DOCUMENTATION_198 && second == DOCUMENTATION_51 && third == DOCUMENTATION_100) return false
        if (first == DOCUMENTATION_203 && second == 0 && third == DOCUMENTATION_113) return false
        if (first >= MULTICAST_MIN) return false
        return true
    }

    /**
     * グローバルユニキャストは 2000::/3。その中の文書用、ベンチマーク用、Teredo、ORCHID は到達できない。
     * リンクローカル、ULA、マルチキャストはこの範囲の外
     */
    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != IPV6_LENGTH) return false
        if (bytes.u8(0) and GLOBAL_UNICAST_MASK != GLOBAL_UNICAST_VALUE) return false
        if (isTeredo(bytes) || isDocumentation(bytes) || isBenchmarking(bytes) || isOrchid(bytes)) return false
        return true
    }

    private fun isTeredo(bytes: ByteArray): Boolean = bytes.u8(0) == 0x20 && bytes.u8(1) == 0x01 && bytes.u8(2) == 0x00 && bytes.u8(3) == 0x00

    private fun isDocumentation(bytes: ByteArray): Boolean =
        (bytes.u8(0) == 0x20 && bytes.u8(1) == 0x01 && bytes.u8(2) == 0x0d && bytes.u8(3) == 0xb8) ||
            (bytes.u8(0) == 0x3f && bytes.u8(1) == 0xff && bytes.u8(2) and 0xf0 == 0x00)

    private fun isBenchmarking(bytes: ByteArray): Boolean =
        bytes.u8(0) == 0x20 &&
            bytes.u8(1) == 0x01 &&
            bytes.u8(2) == 0x00 &&
            bytes.u8(3) == 0x02 &&
            bytes.u8(4) == 0x00 &&
            bytes.u8(5) == 0x00

    private fun isOrchid(bytes: ByteArray): Boolean =
        bytes.u8(0) == 0x20 &&
            bytes.u8(1) == 0x01 &&
            bytes.u8(2) == 0x00 &&
            (bytes.u8(3) and 0xf0 == 0x10 || bytes.u8(3) and 0xf0 == 0x20)

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

    private fun ByteArray.prefixIsZero(length: Int): Boolean = (0 until length).all { this[it] == 0.toByte() }

    private const val IPV4_LENGTH = 4
    private const val IPV6_LENGTH = 16
    private const val IPV4_EMBEDDED_AT = 12
    private const val MAPPED_PREFIX_LENGTH = 10
    private const val NAT64_PREFIX_LENGTH = 4
    private const val SIXTO4_IPV4_AT = 2

    private const val LOOPBACK_OCTET = 127
    private const val RFC1918_172 = 172
    private const val RFC1918_172_MIN = 16
    private const val RFC1918_172_MAX = 31
    private const val RFC1918_192 = 192
    private const val RFC1918_168 = 168
    private const val DEPRECATED_6TO4_ANYCAST = 88
    private const val DEPRECATED_6TO4_ANYCAST_THIRD = 99
    private const val LINK_LOCAL_169 = 169
    private const val LINK_LOCAL_254 = 254
    private const val CGNAT_100 = 100
    private const val CGNAT_MIN = 64
    private const val CGNAT_MAX = 127
    private const val BENCHMARK_198 = 198
    private const val BENCHMARK_MIN = 18
    private const val BENCHMARK_MAX = 19
    private const val DOCUMENTATION_198 = 198
    private const val DOCUMENTATION_51 = 51
    private const val DOCUMENTATION_100 = 100
    private const val DOCUMENTATION_203 = 203
    private const val DOCUMENTATION_113 = 113
    private const val MULTICAST_MIN = 224

    private const val GLOBAL_UNICAST_MASK = 0xe0
    private const val GLOBAL_UNICAST_VALUE = 0x20
}
