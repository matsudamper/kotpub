package net.matsudamper.kotpub.http

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicAddressesTest {
    @Test
    fun `内部や特殊用途のアドレスは公開アドレスではない`() {
        val addresses =
            listOf(
                "0.0.0.0",
                "10.0.0.1",
                "100.64.0.1",
                "100.127.255.255",
                "127.0.0.1",
                "169.254.169.254",
                "172.16.0.1",
                "172.31.255.255",
                "192.0.0.1",
                "192.0.2.1",
                "192.88.99.1",
                "192.168.1.1",
                "198.18.0.1",
                "198.19.255.255",
                "198.51.100.1",
                "203.0.113.1",
                "224.0.0.1",
                "240.0.0.1",
                "255.255.255.255",
                "::",
                "::1",
                "::a00:1",
                "::ffff:0:a00:1",
                "64:ff9b::a00:1",
                "64:ff9b::7f00:1",
                "64:ff9b:1::808:808",
                "100::1",
                "2001::1",
                "2001:1ff::1",
                "2001:db8::1",
                "2002:808:808::1",
                "3fff::1",
                "5f00::1",
                "fc00::1",
                "fd12:3456::1",
                "fe80::1",
                "fec0::1",
                "ff02::1",
            )

        for (address in addresses) {
            assertFalse(PublicAddresses.isPublic(InetAddress.getByName(address)), address)
        }
    }

    @Test
    fun `インターネット上のアドレスは公開アドレス`() {
        val addresses =
            listOf(
                "1.1.1.1",
                "8.8.8.8",
                "11.0.0.1",
                "100.63.255.255",
                "100.128.0.1",
                "169.253.255.255",
                "172.15.255.255",
                "172.32.0.1",
                "192.167.255.255",
                "198.20.0.1",
                "223.255.255.255",
                "2001:200::1",
                "2001:4860:4860::8888",
                "2606:4700:4700::1111",
                "64:ff9b::808:808",
            )

        for (address in addresses) {
            assertTrue(PublicAddresses.isPublic(InetAddress.getByName(address)), address)
        }
    }

    @Test
    fun `IPv4 射影アドレスは中の IPv4 で判定される`() {
        assertFalse(PublicAddresses.isPublic(InetAddress.getByName("::ffff:127.0.0.1")))
        assertFalse(PublicAddresses.isPublic(InetAddress.getByName("::ffff:169.254.169.254")))
        assertTrue(PublicAddresses.isPublic(InetAddress.getByName("::ffff:8.8.8.8")))
    }
}
