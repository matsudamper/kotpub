package net.matsudamper.activitypub.collection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.matsudamper.activitypub.activity.ActivityStreamsIri
import net.matsudamper.activitypub.activitypub.StringListSerializer

/**
 * 順序のある集合。フォロワーや投稿の一覧を返すときの形。
 *
 * 中身は [OrderedCollectionPage] に分けて返し、こちらは総数と最初のページへの
 * 入口だけを持つ。フォロワーが増えても 1 回の応答が際限なく大きくならないようにする。
 */
@Serializable
data class OrderedCollection(
    @SerialName("@context")
    @Serializable(with = StringListSerializer::class)
    val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT,
    @SerialName("id")
    val id: String,
    @SerialName("type")
    val type: String = TYPE,
    @SerialName("totalItems")
    val totalItems: Long,
    /**
     * 最初のページの URL。中身が無くても返す。空の集合と壊れた集合を区別できるようにする
     */
    @SerialName("first")
    val first: String,
) {
    companion object {
        const val TYPE: String = "OrderedCollection"
    }
}

/**
 * [OrderedCollection] の 1 ページ。
 *
 * 中身の型を型引数にしているのは、フォロワーではアクターの URL の文字列が、
 * `outbox` ではアクティビティが並ぶため。serializer は呼び出し側が
 * `OrderedCollectionPage.serializer(Foo.serializer())` の形で渡すので、
 * リフレクションでの解決は起きない。
 *
 * @param partOf このページが属する集合の id
 * @param next 次のページの URL。最後のページなら null にして、キーごと出さない
 */
@Serializable
data class OrderedCollectionPage<T>(
    @SerialName("@context")
    @Serializable(with = StringListSerializer::class)
    val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT,
    @SerialName("id")
    val id: String,
    @SerialName("type")
    val type: String = TYPE,
    @SerialName("totalItems")
    val totalItems: Long,
    @SerialName("partOf")
    val partOf: String,
    @SerialName("orderedItems")
    val orderedItems: List<T>,
    @SerialName("next")
    val next: String? = null,
) {
    companion object {
        const val TYPE: String = "OrderedCollectionPage"
    }
}

/**
 * 1 ページの件数。フォロワーの一覧と outbox で揃えてある
 */
const val COLLECTION_PAGE_SIZE: Int = 12

/**
 * featured に載せる投稿の件数。
 *
 * Mastodon はプロフィールを開いたとき featured を引きに来る。outbox は
 * フォロー後のバックフィル用で、未フォローでは読まない。
 */
const val FEATURED_COLLECTION_SIZE: Int = 20

/**
 * 中身をそのまま返す [OrderedCollection]。
 *
 * outbox のように 2 段構えにしないコレクション向け。Mastodon の featured は
 * 1 回の GET で orderedItems を読む。
 */
@Serializable
data class OrderedCollectionWithItems<T>(
    @SerialName("@context")
    @Serializable(with = StringListSerializer::class)
    val context: List<String> = ActivityStreamsIri.DEFAULT_CONTEXT,
    @SerialName("id")
    val id: String,
    @SerialName("type")
    val type: String = OrderedCollection.TYPE,
    @SerialName("totalItems")
    val totalItems: Long,
    @SerialName("orderedItems")
    val orderedItems: List<T>,
)

/**
 * ページを指すクエリパラメータの名前。
 *
 * 値は直前のページの最後の 1 件を指す cursor。空文字なら先頭のページ。
 * 何件目かで指さないのは、読んでいる間に増減があると同じものが 2 回出たり
 * 抜けたりするため。相手にとって `first` も `next` も中身を読まない URL なので、
 * ページ番号である必要が無い。
 */
const val COLLECTION_CURSOR_PARAM: String = "cursor"
