package net.matsudamper.activitypub.actor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 有効期限つきのキーバリューキャッシュ。
 *
 * 相手のアクター文書を持ち回るためだけのもので、モジュールの外には出さない。
 * 元は `:backend:repository` に置いていたが、そちらは SQLite と jOOQ を抱える
 * アプリ側のモジュールで、ActivityPub の実装が依存するものではない。
 * このモジュールだけで完結させるために持ってきた。
 *
 * interface にしてあるのは差し替えのため。プロセスをまたいでキャッシュしたくなったら
 * [createExpiringCache] の戻りを変えれば済む。
 *
 * キーは外から来る値（相手が名乗った `keyId`）になりうる。使い捨てのキーを
 * いくらでも作れる以上、期限切れの行は読み直されなくても片付ける必要がある。
 */
internal interface ExpiringCache<K : Any, V : Any> {
    /** 有効期限内なら値を返す。無い場合と期限切れの場合はどちらも null */
    fun get(key: K): V?

    /** [ttlMillis] だけ経ったら自動的に無効になる値を保存する */
    fun put(
        key: K,
        value: V,
        ttlMillis: Long,
    )

    /**
     * 有効期限内の値が無いときだけ保存して true を返す。
     *
     * [get] で確かめてから [put] すると、その間に同じことをした別のスレッドと
     * 両方が通る。「1 つだけ通す」ことが要る側はこちらを使う
     */
    fun tryPut(
        key: K,
        value: V,
        ttlMillis: Long,
    ): Boolean

    /** 期限を待たずに捨てる。覚えている値が古いと分かったときに使う */
    fun invalidate(key: K)
}

/**
 * [ExpiringCache] を作る。
 *
 * 実装はプロセスのメモリ上に持つだけで、再起動やプロセス間では共有しない。
 *
 * @param maxEntries 覚えておく行数の上限。溢れた分は捨てる。どれが残るかは決まらない
 */
internal fun <K : Any, V : Any> createExpiringCache(maxEntries: Int): ExpiringCache<K, V> =
    InMemoryExpiringCache(maxEntries)

private class InMemoryExpiringCache<K : Any, V : Any>(
    private val maxEntries: Int,
) : ExpiringCache<K, V> {
    private val entries = ConcurrentHashMap<K, Entry<V>>()

    /**
     * 保存した回数。期限切れを片付ける頻度を決めるのに使う
     */
    private val puts = AtomicInteger()

    override fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (entry.isExpired()) {
            // 期限切れ。remove(key, value) にしておくと、他スレッドが先に
            // 新しい値を put していた場合にそれを消してしまわない
            entries.remove(key, entry)
            return null
        }
        return entry.value
    }

    override fun put(
        key: K,
        value: V,
        ttlMillis: Long,
    ) {
        entries[key] = Entry(value = value, expiresAtMillis = System.currentTimeMillis() + ttlMillis)
        pruneIfNeeded(inserted = key)
    }

    override fun invalidate(key: K) {
        entries.remove(key)
    }

    override fun tryPut(
        key: K,
        value: V,
        ttlMillis: Long,
    ): Boolean {
        val entry = Entry(value = value, expiresAtMillis = System.currentTimeMillis() + ttlMillis)

        // 入れ替えるのは期限切れの行だけ。compute の中は同じキーについて 1 つずつ実行される
        val stored = entries.compute(key) { _, existing ->
            if (existing != null && !existing.isExpired()) existing else entry
        }

        pruneIfNeeded(inserted = key)
        return stored === entry
    }

    /**
     * 期限切れの行を片付け、それでも上限を超えていれば溢れた分を捨てる。
     *
     * [get] だけに任せると、二度と読まれないキーの行が残り続ける。キーは外から
     * いくらでも作れるので、放っておくとリクエストを重ねるだけでメモリが増える。
     * 片付けは保存のたびではなく間隔を空けて行うが、それだと送り込むのをやめた後に
     * 期限切れになった行が残るので、行数の上限でも縛る。
     *
     * 捨てる行を期限の近さで選ばないのは、上限に張り付いた状態で 1 行入るたびに
     * 全部を並べ替えることになるため。上限まで埋めてから 1 行ずつ送り込むだけで、
     * その並べ替えにこちらの CPU を使わせられる。どの行を捨てても、次に要るときに
     * 取り直すだけで判断は変わらない。
     *
     * ただし今入れた行だけは捨てない。この呼び出しが書いたものをその場で捨てると、
     * 上限まで埋めた状態では書いても残らなくなり、間隔を空けるために使っている側
     * （[net.matsudamper.activitypub.actor.HttpRemoteActors] の取り直し）で
     * 間隔が効かなくなる
     *
     * @param inserted この呼び出しで書いた行のキー
     */
    private fun pruneIfNeeded(inserted: K) {
        if (puts.incrementAndGet() % PRUNE_INTERVAL == 0) {
            entries.entries.removeIf { it.value.isExpired() }
        }

        var excess = entries.size - maxEntries
        if (excess <= 0) return

        val iterator = entries.entries.iterator()
        while (excess > 0 && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == inserted) continue

            // remove(key, value) にしておくと、他スレッドが先に新しい値を
            // 入れていた場合にそれを消してしまわない
            if (entries.remove(entry.key, entry.value)) excess--
        }
    }

    private class Entry<V>(
        val value: V,
        val expiresAtMillis: Long,
    ) {
        fun isExpired(): Boolean = expiresAtMillis <= System.currentTimeMillis()
    }

    private companion object {
        /**
         * 片付けるまでに受け付ける保存の回数。
         *
         * 残りうる期限切れの行数の上限がこれで決まる。小さくすると片付けが増え、
         * 大きくすると残る行が増える
         */
        const val PRUNE_INTERVAL = 256
    }
}
