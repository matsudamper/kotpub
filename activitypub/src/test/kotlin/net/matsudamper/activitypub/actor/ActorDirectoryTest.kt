package net.matsudamper.activitypub.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.matsudamper.activitypub.FakeStoredActorNames

// WebFinger とパスで判定がずれると「検索には出るが開けない」という
// 分かりにくい壊れ方をするので、両方の入口を同じだけ確かめる。
class ActorDirectoryTest {
    private val stored = mutableListOf("admin", "feed1", "Gihyo")

    private val directory = ActorDirectory(
        domain = "example.com",
        stored = FakeStoredActorNames(stored),
    )

    @Test
    fun `保存されているアカウントを引ける`() {
        assertEquals("https://example.com/users/admin", directory.resolve("admin")?.actorId)
        assertEquals("https://example.com/users/feed1", directory.resolve("feed1")?.actorId)
        // Mastodon 側の扱いに合わせて大文字小文字は区別しない
        assertEquals("https://example.com/users/admin", directory.resolve("ADMIN")?.actorId)
    }

    @Test
    fun `複数アカウントをまとめて引ける`() {
        val resolved = directory.resolve(setOf("admin", "ADMIN", "feed1", "gihyo", "other", "invalid/name"))

        assertEquals(listOf("admin", "ADMIN", "feed1", "gihyo"), resolved.map { it.key })
        assertEquals("https://example.com/users/admin", resolved["admin"]?.actorId)
        assertEquals("https://example.com/users/admin", resolved["ADMIN"]?.actorId)
        assertEquals("https://example.com/users/feed1", resolved["feed1"]?.actorId)
        assertEquals("https://example.com/users/Gihyo", resolved["gihyo"]?.actorId)
        assertNull(resolved["other"])
        assertNull(resolved["invalid/name"])
    }

    @Test
    fun `Actor の id から引ける`() {
        assertEquals("https://example.com/users/feed1", directory.resolveActorId("https://example.com/users/feed1")?.actorId)
        assertNull(directory.resolveActorId("https://other.example/users/feed1"))
        assertNull(directory.resolveActorId("https://example.com/users/feed1/inbox"))
        assertNull(directory.resolveActorId("https://example.com/users/"))
    }

    @Test
    fun `保存されている綴りで返る`() {
        // 要求された綴りをそのまま返すと、同じアカウントが 2 つの ID で相手側にキャッシュされる
        assertEquals("https://example.com/users/Gihyo", directory.resolve("gihyo")?.actorId)
    }

    @Test
    fun `使えない文字を含む名前は保存先まで引きに行かない`() {
        stored += "feed1/inbox"

        assertNull(directory.resolve("feed1/inbox"))
        assertNull(directory.resolve("feed1@example.com"))
        assertNull(directory.resolve("あ"))
        assertNull(directory.resolve(null))
        assertNull(directory.resolve(""))
    }

    @Test
    fun `関係ない名前は引けない`() {
        assertNull(directory.resolve("other"))
        assertNull(directory.resolve("test-1"))
    }

    @Test
    fun `resource は acct でも Actor の URL でも引ける`() {
        assertEquals("acct:admin@example.com", directory.resolveResource("acct:admin@example.com")?.acct)
        assertEquals("acct:admin@example.com", directory.resolveResource("admin@example.com")?.acct)
        assertEquals(
            "acct:admin@example.com",
            directory.resolveResource("https://example.com/users/admin")?.acct,
        )
        assertEquals("acct:feed1@example.com", directory.resolveResource("acct:feed1@example.com")?.acct)
        assertEquals(
            "acct:feed1@example.com",
            directory.resolveResource("https://example.com/users/feed1")?.acct,
        )
    }

    @Test
    fun `resource の大文字小文字と前後の空白は無視する`() {
        assertEquals("acct:admin@example.com", directory.resolveResource("ACCT:Admin@Example.com")?.acct)
        assertEquals("acct:admin@example.com", directory.resolveResource(" acct:admin@example.com ")?.acct)
    }

    @Test
    fun `別ドメインや壊れた resource は引けない`() {
        assertNull(directory.resolveResource("acct:admin@example.org"))
        assertNull(directory.resolveResource("https://example.org/users/admin"))
        assertNull(directory.resolveResource("acct:admin"))
        assertNull(directory.resolveResource("acct:@example.com"))
        assertNull(directory.resolveResource("https://example.com/users/feed1/inbox"))
        assertNull(directory.resolveResource("https://example.com/users/"))
        assertNull(directory.resolveResource(""))
        assertNull(directory.resolveResource("   "))
    }
}
