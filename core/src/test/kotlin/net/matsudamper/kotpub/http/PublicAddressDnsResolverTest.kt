package net.matsudamper.kotpub.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * 名前解決を伴わないよう、IP アドレスの表記だけを渡す
 */
class PublicAddressDnsResolverTest {
    @Test
    fun `公開アドレスはそのまま返る`() {
        assertEquals(listOf("8.8.8.8"), runBlocking { PublicAddressDnsResolver.resolve("8.8.8.8") })
    }

    @Test
    fun `公開アドレスではない宛先は例外になる`() {
        for (hostname in listOf("127.0.0.1", "169.254.169.254", "::1", "[::1]", "::ffff:10.0.0.1")) {
            assertFailsWith<NonPublicAddressException>(hostname) {
                runBlocking { PublicAddressDnsResolver.resolve(hostname) }
            }
        }
    }
}
