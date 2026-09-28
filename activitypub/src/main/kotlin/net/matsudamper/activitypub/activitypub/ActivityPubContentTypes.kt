package net.matsudamper.activitypub.activitypub

import net.matsudamper.activitypub.http.MediaType

/**
 * ActivityPub と WebFinger で使う Content-Type。
 *
 * `application/json` で返すと Mastodon はアクターとして認識せず、
 * 検索してもプロフィールが出てこない。レスポンスごとにここの定数を明示する。
 */
object ActivityPubContentTypes {
    /** ActivityPub の標準。アクターやアクティビティを返すときはこれ */
    val ActivityJson: MediaType = MediaType("application", "activity+json")

    /**
     * JSON-LD 形式。仕様上は profile パラメータ付きで指定される。
     * 受信時の Accept ヘッダとして飛んでくるので、受け付けられるようにしておく。
     */
    val LdJson: MediaType = MediaType("application", "ld+json")

    /** WebFinger (RFC 7033) のレスポンス用 */
    val JrdJson: MediaType = MediaType("application", "jrd+json")

    /** [negotiate] が選ぶ候補。先に書いたものが優先される */
    private val negotiable: List<MediaType> = listOf(ActivityJson, LdJson)

    /**
     * `Accept` ヘッダを見て、アクターやアクティビティを返すときの Content-Type を選ぶ。
     *
     * Mastodon は `application/activity+json` と、profile パラメータ付きの
     * `application/ld+json` のどちらでも取りに来る。要求された方で返さないと
     * 実装によっては解釈してもらえない。
     *
     * 判断できない場合は [ActivityJson] を返す。`Accept` を送ってこない相手や
     * `*&#47;*` だけの相手に `application/json` を返すと、アクターとして認識されないため。
     */
    fun negotiate(acceptHeader: String?): MediaType {
        if (acceptHeader.isNullOrBlank()) return ActivityJson

        // ld+json は profile パラメータ付きで飛んでくるので、パラメータは落として突き合わせる
        val ranges = acceptHeader.split(',').mapNotNull { AcceptedRange.parse(it) }

        // 品質値が同じなら negotiable に先に書いた方を選ぶ。maxByOrNull は最初の最大を返す
        return negotiable
            .map { candidate -> candidate to qualityOf(candidate, ranges) }
            .filter { (_, quality) -> quality > 0.0 }
            .maxByOrNull { (_, quality) -> quality }
            ?.first
            ?: ActivityJson
    }

    /**
     * [candidate] に当たる指定のうち、最も具体的なものの品質値。当たるものが無ければ 0。
     *
     * `application/&#42;;q=1, application/activity+json;q=0` のように、ワイルドカードで広く許しつつ
     * 特定の型だけ断ることができる。ワイルドカードの品質値で決めると、断られた型を返してしまう
     */
    private fun qualityOf(
        candidate: MediaType,
        ranges: List<AcceptedRange>,
    ): Double =
        ranges
            .filter { candidate.matches(it.pattern) }
            .maxByOrNull { it.specificity }
            ?.quality
            ?: 0.0

    /**
     * `Accept` の 1 項目
     *
     * @param quality `q=` の値。書かれていないか読めなければ 1
     */
    private class AcceptedRange(
        val pattern: MediaType,
        val quality: Double,
    ) {
        val specificity: Int =
            when {
                pattern.type == MediaType.WILDCARD -> 0
                pattern.subtype == MediaType.WILDCARD -> 1
                else -> 2
            }

        companion object {
            /**
             * 読めない項目は null。inbox は誰でも叩けるので、何が入っていても例外を投げない
             */
            fun parse(raw: String): AcceptedRange? {
                val pattern = MediaType.parseWithoutParameters(raw) ?: return null
                val quality =
                    raw.split(';')
                        .drop(1)
                        .map { it.trim() }
                        .firstOrNull { it.startsWith("q=", ignoreCase = true) }
                        ?.substring(2)
                        ?.toDoubleOrNull()
                        ?.takeIf { it in 0.0..1.0 }
                        ?: 1.0

                return AcceptedRange(pattern = pattern, quality = quality)
            }
        }
    }
}
