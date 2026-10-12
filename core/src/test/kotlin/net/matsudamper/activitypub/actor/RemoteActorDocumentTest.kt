package net.matsudamper.activitypub.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.matsudamper.activitypub.json.AppJson

// 相手のアクター文書から、一覧に出すための部分を読む。
// この文書は署名の検証にも使うので、表示のための項目で全体を落とさないこと。
class RemoteActorDocumentTest {
    private fun profile(json: String): RemoteActorProfile =
        AppJson.decodeFromString(RemoteActorDocument.serializer(), json).profile()

    @Test
    fun `文字列で名乗った名前とアイコンを読む`() {
        val profile = profile(
            """
            {"id":"https://remote.example/users/alice","preferredUsername":"alice","name":"アリス",
             "url":"https://remote.example/@alice","icon":{"type":"Image","url":"https://files.remote.example/a.png"}}
            """.trimIndent(),
        )

        assertEquals("alice", profile.preferredUsername)
        assertEquals("アリス", profile.displayName)
        assertEquals("https://remote.example/@alice", profile.profileUrl)
        assertEquals("https://files.remote.example/a.png", profile.iconUrl)
    }

    @Test
    fun `名前が配列でも文書ごと落とさず先頭を読む`() {
        val profile = profile("""{"id":"https://remote.example/users/alice","name":["アリス","Alice"]}""")

        assertEquals("アリス", profile.displayName)
    }

    @Test
    fun `名前がオブジェクトなら名乗っていない扱い`() {
        val profile = profile("""{"id":"https://remote.example/users/alice","name":{"ja":"アリス"}}""")

        assertNull(profile.displayName)
    }

    @Test
    fun `配列の先頭が使えない URL なら後ろから拾う`() {
        val profile = profile(
            """
            {"id":"https://remote.example/users/alice",
             "url":["http://remote.example/@alice","https://remote.example/@alice"],
             "icon":[{"type":"Image","url":"javascript:alert(1)"},"https://files.remote.example/a.png"]}
            """.trimIndent(),
        )

        assertEquals("https://remote.example/@alice", profile.profileUrl)
        assertEquals("https://files.remote.example/a.png", profile.iconUrl)
    }

    @Test
    fun `https でない URL は名乗っていない扱い`() {
        val profile = profile(
            """{"id":"https://remote.example/users/alice","url":"http://remote.example/@alice","icon":"data:image/png;base64,AAAA"}""",
        )

        assertNull(profile.profileUrl)
        assertNull(profile.iconUrl)
    }
}
