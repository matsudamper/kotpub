package net.matsudamper.activitypub.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HttpUrlTest {
    @Test
    fun `Unicodeのホスト名はpunycodeで読む`() {
        val url = assertNotNull(HttpUrl.parseHttps("https://例え.テスト:8443/users/あ?q=い"))

        assertEquals("xn--r8jz45g.xn--zckzah", url.host)
        assertEquals(8443, url.port)
        assertEquals("/users/%E3%81%82", url.rawPath)
        assertEquals("q=%E3%81%84", url.rawQuery)
    }

    @Test
    fun `ASCIIのホスト名はそのまま読む`() {
        val url = assertNotNull(HttpUrl.parseHttps("https://example.com/users/admin"))

        assertEquals("example.com", url.host)
        assertEquals(-1, url.port)
        assertEquals("/users/admin", url.rawPath)
        assertNull(url.rawQuery)
    }

    @Test
    fun `同じホストかはUnicodeとpunycodeの綴りの違いを問わない`() {
        val unicode = assertNotNull(HttpUrl.parse("https://例え.テスト/users/a"))
        val punycode = assertNotNull(HttpUrl.parse("https://xn--r8jz45g.xn--zckzah/users/a"))

        assertEquals(true, unicode.isSameHost(punycode))
    }

    @Test
    fun `ホストの無いものとURLとして読めないものは読まない`() {
        assertNull(HttpUrl.parse("not a url"))
        assertNull(HttpUrl.parse("/users/admin"))
        assertNull(HttpUrl.parse("https:///users/admin"))
    }
}
