package net.matsudamper.activitypub.activitypub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import net.matsudamper.activitypub.json.AppJson

// ActivityPub の @context / to / cc / type のように、
// 単一文字列と配列のどちらでも来るフィールドの正規化を確認する。
// 読み込みは常にリストへ、書き出しは 1 要素なら文字列に戻す。
class StringListSerializerTest {
    @Serializable
    private data class Sample(
        @SerialName("to")
        @Serializable(with = StringListSerializer::class)
        val to: List<String>,
    )

    @Test
    fun `単一の文字列は1要素のリストに正規化される`() {
        val decoded = AppJson.decodeFromString<Sample>("""{"to":"https://example.com/a"}""")

        assertEquals(listOf("https://example.com/a"), decoded.to)
    }

    @Test
    fun `配列はそのままリストになる`() {
        val decoded =
            AppJson.decodeFromString<Sample>(
                """{"to":["https://example.com/a","https://example.com/b"]}""",
            )

        assertEquals(listOf("https://example.com/a", "https://example.com/b"), decoded.to)
    }

    @Test
    fun `空配列は空リストになる`() {
        val decoded = AppJson.decodeFromString<Sample>("""{"to":[]}""")

        assertEquals(emptyList(), decoded.to)
    }

    @Test
    fun `nullは空リストとして扱う`() {
        val decoded = AppJson.decodeFromString<Sample>("""{"to":null}""")

        assertEquals(emptyList(), decoded.to)
    }

    @Test
    fun `1要素のリストは文字列として出力する`() {
        val encoded = AppJson.encodeToString(Sample(to = listOf("https://example.com/a")))

        assertEquals("""{"to":"https://example.com/a"}""", encoded)
    }

    @Test
    fun `2要素以上のリストは配列として出力する`() {
        val encoded =
            AppJson.encodeToString(
                Sample(to = listOf("https://example.com/a", "https://example.com/b")),
            )

        assertEquals("""{"to":["https://example.com/a","https://example.com/b"]}""", encoded)
    }

    @Test
    fun `空リストは空配列として出力する`() {
        val encoded = AppJson.encodeToString(Sample(to = emptyList()))

        assertEquals("""{"to":[]}""", encoded)
    }

    @Test
    fun `文字列以外が来たら例外になる`() {
        assertFailsWith<SerializationException> {
            AppJson.decodeFromString<Sample>("""{"to":42}""")
        }
        assertFailsWith<SerializationException> {
            AppJson.decodeFromString<Sample>("""{"to":[{"id":"https://example.com/a"}]}""")
        }
    }

    @Test
    fun `デコードとエンコードを往復しても値が変わらない`() {
        val original = """{"to":["https://example.com/a","https://example.com/b"]}"""

        val roundTripped = AppJson.encodeToString(AppJson.decodeFromString<Sample>(original))

        assertEquals(original, roundTripped)
    }
}
