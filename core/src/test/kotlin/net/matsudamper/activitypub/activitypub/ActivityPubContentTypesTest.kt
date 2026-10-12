package net.matsudamper.activitypub.activitypub

import kotlin.test.Test
import kotlin.test.assertEquals

// Accept ヘッダから返す Content-Type を選ぶ部分。
// Ktor の ContentNegotiation を使わない代わりに自前で持っているので、
// Mastodon が実際に送ってくる形を中心に固定する。
class ActivityPubContentTypesTest {
    @Test
    fun `activity+jsonを要求されたらactivity+jsonを返す`() {
        assertEquals(
            ActivityPubContentTypes.ActivityJson,
            ActivityPubContentTypes.negotiate("application/activity+json"),
        )
    }

    @Test
    fun `profile付きのld+jsonを要求されたらld+jsonを返す`() {
        // Mastodon が送ってくる実際の形
        val accept = """application/ld+json; profile="https://www.w3.org/ns/activitystreams""""

        assertEquals(
            ActivityPubContentTypes.LdJson,
            ActivityPubContentTypes.negotiate(accept),
        )
    }

    @Test
    fun `品質値が高い方が選ばれる`() {
        assertEquals(
            ActivityPubContentTypes.LdJson,
            ActivityPubContentTypes.negotiate("application/activity+json;q=0.5, application/ld+json;q=0.9"),
        )
    }

    @Test
    fun `Acceptが無い場合はactivity+jsonを返す`() {
        assertEquals(ActivityPubContentTypes.ActivityJson, ActivityPubContentTypes.negotiate(null))
        assertEquals(ActivityPubContentTypes.ActivityJson, ActivityPubContentTypes.negotiate(""))
    }

    @Test
    fun `ワイルドカードにはactivity+jsonを返す`() {
        assertEquals(ActivityPubContentTypes.ActivityJson, ActivityPubContentTypes.negotiate("*/*"))
        assertEquals(ActivityPubContentTypes.ActivityJson, ActivityPubContentTypes.negotiate("application/*"))
    }

    @Test
    fun `候補に無い型だけを要求されてもactivity+jsonを返す`() {
        // application/json で返すと Mastodon はアクターとして認識しない。
        // 要求に合わせるより activity+json を押し通す方が実害が少ない
        assertEquals(ActivityPubContentTypes.ActivityJson, ActivityPubContentTypes.negotiate("text/html"))
        assertEquals(ActivityPubContentTypes.ActivityJson, ActivityPubContentTypes.negotiate("application/json"))
    }

    @Test
    fun `壊れた値が混ざっていても後続の候補を見る`() {
        // inbox は誰でも叩けるので、Accept に何が入っていても例外を投げない
        assertEquals(
            ActivityPubContentTypes.LdJson,
            ActivityPubContentTypes.negotiate("///, application/ld+json"),
        )
    }

    @Test
    fun `品質値が0の型は選ばない`() {
        assertEquals(
            ActivityPubContentTypes.ActivityJson,
            ActivityPubContentTypes.negotiate("application/ld+json;q=0"),
        )
    }

    @Test
    fun `ワイルドカードで許していても具体的にq=0で断った型は選ばない`() {
        assertEquals(
            ActivityPubContentTypes.LdJson,
            ActivityPubContentTypes.negotiate("application/*;q=1, application/activity+json;q=0"),
        )
    }
}
