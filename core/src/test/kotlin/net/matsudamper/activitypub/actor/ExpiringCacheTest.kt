package net.matsudamper.activitypub.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 相手のサーバーを引きに行く回数を抑える土台。
// 「1 つだけ通す」が壊れると、通らない署名を並べるだけで取得を増やせる。
class ExpiringCacheTest {
    @Test
    fun `期限内なら覚えている値を返す`() {
        val cache = createExpiringCache<String, String>(maxEntries = MAX_ENTRIES)

        cache.put(key = "a", value = "覚えた値", ttlMillis = 60_000)

        assertEquals("覚えた値", cache.get("a"))
    }

    @Test
    fun `期限が切れていれば返さない`() {
        val cache = createExpiringCache<String, String>(maxEntries = MAX_ENTRIES)

        cache.put(key = "a", value = "覚えた値", ttlMillis = 0)

        assertNull(cache.get("a"))
    }

    @Test
    fun `期限内の値があれば tryPut は通らない`() {
        val cache = createExpiringCache<String, String>(maxEntries = MAX_ENTRIES)
        cache.put(key = "a", value = "先に入れた値", ttlMillis = 60_000)

        assertFalse(cache.tryPut(key = "a", value = "後から入れる値", ttlMillis = 60_000))
        assertEquals("先に入れた値", cache.get("a"))
    }

    @Test
    fun `期限が切れていれば tryPut は通る`() {
        val cache = createExpiringCache<String, String>(maxEntries = MAX_ENTRIES)
        cache.put(key = "a", value = "先に入れた値", ttlMillis = 0)

        assertTrue(cache.tryPut(key = "a", value = "後から入れる値", ttlMillis = 60_000))
        assertEquals("後から入れる値", cache.get("a"))
    }

    @Test
    fun `覚えていないキーなら tryPut は通る`() {
        val cache = createExpiringCache<String, String>(maxEntries = MAX_ENTRIES)

        assertTrue(cache.tryPut(key = "a", value = "入れる値", ttlMillis = 60_000))
    }

    @Test
    fun `捨てれば期限内でも返さない`() {
        val cache = createExpiringCache<String, String>(maxEntries = MAX_ENTRIES)
        cache.put(key = "a", value = "覚えた値", ttlMillis = 60_000)

        cache.invalidate("a")

        assertNull(cache.get("a"))
    }

    @Test
    fun `上限を超えたら期限内でも捨てる`() {
        // 使い捨てのキーを送り込まれても、期限が切れるのを待たずに行数が頭打ちになる
        val cache = createExpiringCache<String, String>(maxEntries = MAX_ENTRIES)

        repeat(MAX_ENTRIES * 2) { index ->
            cache.put(key = "key$index", value = "値$index", ttlMillis = 60_000)
        }

        val remaining = (0 until MAX_ENTRIES * 2).count { cache.get("key$it") != null }
        assertTrue(remaining <= MAX_ENTRIES, "上限を超えて残っている: $remaining")
    }

    @Test
    fun `上限を超えても今入れた行は残る`() {
        // 間隔を空けるために使う側は、書いた行が残ることを当てにしている。
        // その場で捨てられると、上限まで埋めた状態で間隔が効かなくなる
        val cache = createExpiringCache<String, String>(maxEntries = MAX_ENTRIES)
        repeat(MAX_ENTRIES) { index ->
            cache.put(key = "key$index", value = "値$index", ttlMillis = 60_000)
        }

        cache.put(key = "最後に入れたキー", value = "最後の値", ttlMillis = 60_000)

        assertEquals("最後の値", cache.get("最後に入れたキー"))
    }

    private companion object {
        const val MAX_ENTRIES = 8
    }
}
