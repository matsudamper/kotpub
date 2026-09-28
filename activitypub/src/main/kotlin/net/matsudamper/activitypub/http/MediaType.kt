package net.matsudamper.activitypub.http

/**
 * パラメータを持たない Content-Type。`type/subtype` の形。
 *
 * 大文字小文字は区別しないので、小文字に揃えて持つ。
 */
class MediaType(
    type: String,
    subtype: String,
) {
    val type: String = type.lowercase()
    val subtype: String = subtype.lowercase()

    /**
     * [pattern] が `*` を含む範囲なら、その範囲に入っているか
     */
    fun matches(pattern: MediaType): Boolean =
        (pattern.type == WILDCARD || pattern.type == type) &&
            (pattern.subtype == WILDCARD || pattern.subtype == subtype)

    override fun equals(other: Any?): Boolean =
        other is MediaType && other.type == type && other.subtype == subtype

    override fun hashCode(): Int = 31 * type.hashCode() + subtype.hashCode()

    override fun toString(): String = "$type/$subtype"

    companion object {
        const val WILDCARD = "*"

        val Json: MediaType = MediaType("application", "json")

        /**
         * パラメータは落とす。`type/subtype` の形でなければ null
         */
        fun parseWithoutParameters(raw: String): MediaType? {
            val parts = raw.substringBefore(';').trim().split('/')
            if (parts.size != 2) return null

            val type = parts[0].trim()
            val subtype = parts[1].trim()
            if (type.isEmpty() || subtype.isEmpty()) return null

            return MediaType(type, subtype)
        }
    }
}
