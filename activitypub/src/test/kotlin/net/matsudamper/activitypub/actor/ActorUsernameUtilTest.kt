package net.matsudamper.activitypub.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActorUsernameUtilTest {
    @Test
    fun `英数字と記号で組み立てた名前は使える`() {
        assertTrue(ActorUsernameUtil.isValid("admin"))
        assertTrue(ActorUsernameUtil.isValid("feed-1"))
        assertTrue(ActorUsernameUtil.isValid("feed.1_2"))
        assertTrue(ActorUsernameUtil.isValid("a"))
    }

    @Test
    fun `空と長すぎる名前は使えない`() {
        assertFalse(ActorUsernameUtil.isValid(""))
        assertTrue(ActorUsernameUtil.isValid("a".repeat(ActorUsernameUtil.MAX_LENGTH)))
        assertFalse(ActorUsernameUtil.isValid("a".repeat(ActorUsernameUtil.MAX_LENGTH + 1)))
    }

    @Test
    fun `使えない文字を重複なく返す`() {
        assertEquals(listOf(' ', '/', 'あ'), ActorUsernameUtil.unusableCharacters("feed 1/あ/あ"))
        assertEquals(emptyList(), ActorUsernameUtil.unusableCharacters("feed-1"))
    }

    @Test
    fun `間には置ける文字でも先頭と末尾なら使えない文字として返る`() {
        assertEquals(listOf('-'), ActorUsernameUtil.unusableCharacters("feed-"))
        assertEquals(listOf('.'), ActorUsernameUtil.unusableCharacters(".feed"))
        // 1 文字の名前は先頭であり末尾でもある
        assertEquals(listOf('-'), ActorUsernameUtil.unusableCharacters("-"))
    }
}
