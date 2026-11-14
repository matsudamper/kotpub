package net.matsudamper.kotpub.collection

import java.time.Instant

/**
 * [COLLECTION_CURSOR_PARAM] に入れる、直前のページの最後の 1 件の位置。時刻と文字列の key で指す
 */
internal data class CollectionCursor(
    val at: Instant,
    val key: String,
) {
    /**
     * 相手が辿るだけの値なので、読める形にしておく必要は無い。
     * 区切りは `_`。key は最後に置くので、key に `_` が入っていても読める
     */
    fun encode(): String = "${at.epochSecond}_${at.nano}_$key"

    companion object {
        /**
         * 読めない形なら null。壊れた cursor は先頭のページとして扱う。
         * 相手に教えても直しようが無いので、拒否はしない
         */
        fun decode(raw: String): CollectionCursor? {
            val parts = raw.split('_', limit = 3)
            if (parts.size != 3) return null

            val epochSecond = parts[0].toLongOrNull() ?: return null
            val nano = parts[1].toLongOrNull() ?: return null
            if (parts[2].isEmpty()) return null

            return runCatching {
                CollectionCursor(at = Instant.ofEpochSecond(epochSecond, nano), key = parts[2])
            }.getOrNull()
        }
    }
}
