package uk.co.tfd.boatwatch.autopilot.protocol

/**
 * Encode and decode SeaSmart ($PCDIN) NMEA sentences for N2K message transport over HTTP.
 *
 * Format: $PCDIN,<PGN_hex>,<timestamp_hex>,<source_hex>,<data_hex>*<checksum>
 */
object SeaSmartCodec {

    data class Frame(
        val pgn: Long,
        val timestamp: Long,
        val source: Int,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return pgn == other.pgn && source == other.source && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = pgn.hashCode()
            result = 31 * result + source
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    fun encode(pgn: Long, source: Int, data: ByteArray): String {
        val pgnHex = String.format("%06X", pgn)
        val tsHex = String.format("%08X", System.currentTimeMillis() and 0x7FFFFFFF)
        val srcHex = String.format("%02X", source)
        val dataHex = data.joinToString("") { String.format("%02X", it) }
        val body = "PCDIN,$pgnHex,$tsHex,$srcHex,$dataHex"
        val checksum = body.fold(0) { acc, c -> acc xor c.code } and 0xFF
        return "\$$body*${String.format("%02X", checksum)}"
    }

    fun decode(line: String): Frame? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("\$PCDIN,")) return null

        val starIdx = trimmed.lastIndexOf('*')
        if (starIdx < 0) return null

        val body = trimmed.substring(1, starIdx) // strip $ and *XX
        val checksumStr = trimmed.substring(starIdx + 1)

        // Verify checksum
        val expectedChecksum = body.fold(0) { acc, c -> acc xor c.code } and 0xFF
        val actualChecksum = checksumStr.toIntOrNull(16) ?: return null
        if (expectedChecksum != actualChecksum) return null

        val parts = body.split(",")
        if (parts.size < 5) return null
        // parts[0] = "PCDIN"

        val pgn = parts[1].toLongOrNull(16) ?: return null
        val timestamp = parts[2].toLongOrNull(16) ?: return null
        val source = parts[3].toIntOrNull(16) ?: return null
        val dataHex = parts[4]
        if (dataHex.length % 2 != 0) return null

        val data = ByteArray(dataHex.length / 2) { i ->
            dataHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        return Frame(pgn, timestamp, source, data)
    }
}
