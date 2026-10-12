package net.matsudamper.activitypub.crypto

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UuidV7Test {
    @Test
    fun `RFC 9562 の version 7 になる`() {
        val id = UuidV7.generate(1_704_067_200_000L)

        assertEquals(7, UUID.fromString(id).version())
    }

    @Test
    fun `先頭 48 bit に Unix 時刻が入る`() {
        val timestampMillis = 1_756_291_200_123L
        val id = UuidV7.generate(timestampMillis)

        assertEquals(timestampMillis, UUID.fromString(id).mostSignificantBits ushr 16)
    }

    @Test
    fun `同じ時刻でも乱数部分で衝突しない`() {
        val timestampMillis = 1_756_291_200_000L
        val first = UuidV7.generate(timestampMillis)
        val second = UuidV7.generate(timestampMillis)

        assertTrue(first != second)
    }

    @Test
    fun `時刻が後の id の方が辞書順で大きい`() {
        val earlier = UuidV7.generate(1_756_291_200_000L)
        val later = UuidV7.generate(1_756_291_200_001L)

        assertTrue(earlier < later)
    }

    @Test
    fun `48 bit に収まらない時刻は拒否する`() {
        assertFailsWith<IllegalArgumentException> {
            UuidV7.generate(1L shl 48)
        }
    }
}
