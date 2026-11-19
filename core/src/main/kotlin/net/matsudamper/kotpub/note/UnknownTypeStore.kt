package net.matsudamper.kotpub.note

/**
 * 届いたものの中にあった、このライブラリが読み方を知らない `type` の記録先。実装は利用側のアプリが用意する。
 *
 * 実装ごとに使う型が違い、実際に届くまで何が来るか分からないので、何が届いているかを確かめるのに使う
 */
fun interface UnknownTypeStore {
    /**
     * @param types 読み方を知らない `type` の名前。重複は除いてある
     * @param sourceUrl 型を見つけた object の id。object に id が無ければ包んでいるアクティビティの id
     */
    suspend fun record(types: List<String>, sourceUrl: String)
}
