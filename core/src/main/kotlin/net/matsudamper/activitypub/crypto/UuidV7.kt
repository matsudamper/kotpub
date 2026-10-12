package net.matsudamper.activitypub.crypto

import java.security.SecureRandom
import java.util.UUID

object UuidV7 {
    private val random = SecureRandom()

    // TODO: Java 26 以降は UUID.ofEpochMillis(timestampMillis).toString() に差し替える

    fun generate(timestampMillis: Long = System.currentTimeMillis()): String {
        require(timestampMillis >= 0) { "timestamp が負" }
        require(timestampMillis shr 48 == 0L) { "timestamp が 48 bit に収まらない" }

        val bytes = ByteArray(16)
        random.nextBytes(bytes)

        bytes[0] = (timestampMillis ushr 40).toByte()
        bytes[1] = (timestampMillis ushr 32).toByte()
        bytes[2] = (timestampMillis ushr 24).toByte()
        bytes[3] = (timestampMillis ushr 16).toByte()
        bytes[4] = (timestampMillis ushr 8).toByte()
        bytes[5] = timestampMillis.toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()

        return bytesToUuid(bytes).toString()
    }
}

private fun bytesToUuid(bytes: ByteArray): UUID {
    var msb = 0L
    var lsb = 0L
    for (index in 0 until 8) {
        msb = (msb shl 8) or (bytes[index].toLong() and 0xff)
    }
    for (index in 8 until 16) {
        lsb = (lsb shl 8) or (bytes[index].toLong() and 0xff)
    }
    return UUID(msb, lsb)
}
