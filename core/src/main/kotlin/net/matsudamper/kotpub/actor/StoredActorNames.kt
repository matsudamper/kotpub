package net.matsudamper.kotpub.actor

/**
 * リクエストで指定された username のアクターがこのサーバーに存在するかを確かめ、
 * 存在すれば保存されているとおりの綴り（大文字小文字）に揃えた username を返す。
 *
 * どこに保存されているかは [ActorDirectory] の関心ではないので、インターフェースだけを決めておく。
 */
interface StoredActorNames {
    /**
     * [username] と大文字小文字を無視して一致する、保存されている username を返す。
     * 該当するアクターが無ければ null。
     *
     * 例: `Feed1` が保存されているとき、`feed1` を渡すと `Feed1` を返す。
     *
     * 返した綴りはアクター ID の組み立てや、他の `Stored*` の引数にそのまま使われる。
     * 要求された綴りをそのまま返すと、`Feed1` と `feed1` が別のアクター ID になり、
     * 相手側に両方がキャッシュされる。
     */
    fun find(username: String): String?

    /**
     * [find] を複数の username でまとめて行う。キーは渡された username で、見つからなかったものは含めない。
     */
    fun finds(usernames: Set<String>): Map<String, String>
}
