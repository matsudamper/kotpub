package net.matsudamper.activitypub.actor

/**
 * 応答するアクターの名前の引き先。
 *
 * どこに保存されているかは [ActorDirectory] の関心ではないので、
 * 名前を渡して引けることだけを決めておく。
 */
interface StoredActorNames {
    /**
     * 保存されている名前を返す。無ければ null。
     *
     * 大文字小文字の揺れは実装側で吸収し、返すのは保存されている綴り。
     * 要求された綴りをそのまま返すと、`Feed1` と `feed1` が別のアクター ID になり、
     * 相手側に両方がキャッシュされる。
     */
    fun find(username: String): String?

    /**
     * 保存されている名前をまとめて引く。
     *
     * 返すマップのキーは要求された名前、値は保存されている綴り。
     */
    fun finds(usernames: Set<String>): Map<String, String>
}
