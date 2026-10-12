package net.matsudamper.activitypub.activitypub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.matsudamper.activitypub.json.AppJson

// Undo や Accept の object のように、
// URL 文字列と埋め込みオブジェクトの両方を取るフィールドを確認する。
// どちらの形で来ても読めて、元の形のまま書き戻せること。
class LinkOrObjectTest {
    @Serializable
    private data class Sample(
        @SerialName("object")
        val `object`: LinkOrObject,
    )

    @Test
    fun `文字列はLinkとして読み込まれる`() {
        val decoded = AppJson.decodeFromString<Sample>("""{"object":"https://example.com/follow/1"}""")

        assertEquals(LinkOrObject.Link("https://example.com/follow/1"), decoded.`object`)
    }

    @Test
    fun `オブジェクトはEmbeddedとして読み込まれる`() {
        val decoded =
            AppJson.decodeFromString<Sample>(
                """{"object":{"id":"https://example.com/follow/1","type":"Follow"}}""",
            )

        val expected =
            buildJsonObject {
                put("id", JsonPrimitive("https://example.com/follow/1"))
                put("type", JsonPrimitive("Follow"))
            }
        assertEquals(LinkOrObject.Embedded(expected), decoded.`object`)
    }

    @Test
    fun `Linkは文字列として出力される`() {
        val encoded = AppJson.encodeToString(Sample(LinkOrObject.Link("https://example.com/follow/1")))

        assertEquals("""{"object":"https://example.com/follow/1"}""", encoded)
    }

    @Test
    fun `Embeddedはオブジェクトのまま出力される`() {
        val json =
            buildJsonObject {
                put("id", JsonPrimitive("https://example.com/follow/1"))
                put("type", JsonPrimitive("Follow"))
            }

        val encoded = AppJson.encodeToString(Sample(LinkOrObject.Embedded(json)))

        assertEquals("""{"object":{"id":"https://example.com/follow/1","type":"Follow"}}""", encoded)
    }

    @Test
    fun `文字列でもオブジェクトでもない場合は例外になる`() {
        assertFailsWith<SerializationException> {
            AppJson.decodeFromString<Sample>("""{"object":42}""")
        }
    }
}
