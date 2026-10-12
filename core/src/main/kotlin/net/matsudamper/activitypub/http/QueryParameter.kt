package net.matsudamper.activitypub.http

/**
 * クエリパラメータの値に入れるための percent-encoding。
 *
 * RFC 3986 の unreserved 以外は全て符号化する。`URLEncoder` は空白を `+` にするので使わない
 */
internal object QueryParameter {
    fun encode(value: String): String =
        buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val char = (byte.toInt() and 0xFF).toChar()
                if (char.isUnreserved()) {
                    append(char)
                } else {
                    append('%')
                    append("%02X".format(byte.toInt() and 0xFF))
                }
            }
        }

    private fun Char.isUnreserved(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"
}
