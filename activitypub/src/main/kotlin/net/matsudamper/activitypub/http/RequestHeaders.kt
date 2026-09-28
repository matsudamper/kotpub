package net.matsudamper.activitypub.http

/**
 * 受信したリクエストのヘッダ。
 *
 * サーバーの実装を選ばずに署名の検証と inbox の処理を書くため、
 * 必要な読み取りだけをここで決めておく。
 */
interface RequestHeaders {
    /**
     * 名前の大文字小文字は区別しない。無ければ null。
     *
     * 同じ名前が複数回来た場合は届いた順に並べる。署名文字列はこれを `, ` で繋いで作るので、
     * 並びを変えると検証が通らなくなる
     */
    fun getAll(name: String): List<String>?

    operator fun get(name: String): String? = getAll(name)?.firstOrNull()

    companion object {
        fun of(values: Map<String, List<String>>): RequestHeaders = MapRequestHeaders(values)

        fun ofSingleValues(values: Map<String, String>): RequestHeaders =
            MapRequestHeaders(values.mapValues { (_, value) -> listOf(value) })
    }
}

private class MapRequestHeaders(
    values: Map<String, List<String>>,
) : RequestHeaders {
    private val valuesByLowercaseName: Map<String, List<String>> =
        values.entries
            .groupBy(keySelector = { it.key.lowercase() }, valueTransform = { it.value })
            .mapValues { (_, lists) -> lists.flatten() }

    override fun getAll(name: String): List<String>? = valuesByLowercaseName[name.lowercase()]
}
