package net.matsudamper.kotpub.actor

/**
 * このサーバーにいるローカルアクターの username を、保存先から探すためのインターフェース。
 *
 * username は `https://{domain}/users/{username}` や
 * `acct:{username}@{domain}` の `{username}` 部分（例: `Feed1`）。
 * 表示名（`name`）ではない。
 *
 * どこに保存されているかは [ActorDirectory] の関心ではないので、
 * username を渡すと保存されている username が返ってくることだけを決めておく。
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
     * [find] を複数の username でまとめて行う。
     *
     * 返すマップのキーは渡された username、値は保存されている username。
     * 該当するアクターが無い username はキーに含めない。
     *
     * 例: `Feed1` だけが保存されているとき、`{feed1, unknown}` を渡すと
     * `{feed1 -> Feed1}` を返す。
     */
    fun finds(usernames: Set<String>): Map<String, String>
}
