package net.matsudamper.activitypub.actor

import kotlin.test.Test
import kotlin.test.assertEquals

// URL の綴りは一度 Mastodon 側にキャッシュされると直せないので、
// 組み立ての結果を文字列として固定しておく。
class ActorUrlsTest {
    @Test
    fun `ドメインとユーザー名から URL を組み立てる`() {
        val urls = ActorUrls(domain = "example.com", username = "admin")

        assertEquals("acct:admin@example.com", urls.acct)
        assertEquals("https://example.com/users/admin", urls.actorId)
        assertEquals("https://example.com/users/admin/inbox", urls.inbox)
        assertEquals("https://example.com/users/admin/outbox", urls.outbox)
        assertEquals("https://example.com/users/admin/collections/featured", urls.featured)
        assertEquals("https://example.com/users/admin/followers", urls.followers)
        assertEquals("https://example.com/users/admin/following", urls.following)
        assertEquals("https://example.com/users/admin/header", urls.header)
        assertEquals("https://example.com/users/admin/header?v=abc123", urls.header("abc123"))
        assertEquals("https://example.com/users/admin#main-key", urls.publicKeyId)
    }

    @Test
    fun `使い捨てアクターでも同じ形になる`() {
        val urls = ActorUrls(domain = "example.com", username = "test-1")

        assertEquals("acct:test-1@example.com", urls.acct)
        assertEquals("https://example.com/users/test-1", urls.actorId)
        assertEquals("https://example.com/users/test-1#main-key", urls.publicKeyId)
    }
}
