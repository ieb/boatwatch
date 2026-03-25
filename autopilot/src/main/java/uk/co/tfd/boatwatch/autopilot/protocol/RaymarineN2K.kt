package uk.co.tfd.boatwatch.autopilot.protocol

/**
 * Constructs PGN 126208 command payloads for Raymarine autopilot control.
 *
 * Command format (from real p70 bus captures):
 *   Mode change (17 bytes):  01 63 ff 00 f8 04 01 3b 07 03 04 04 [MODE] [QUAL] 05 [SUB_HI] [SUB_LO]
 *   Heading set (14 bytes):  01 50 ff 00 f8 03 01 3b 07 03 04 06 [LO] [HI]
 *   Wind datum  (14 bytes):  01 41 ff 00 f8 03 01 3b 07 03 04 04 [LO] [HI]
 */
object RaymarineN2K {

    const val PGN_COMMAND: Long = 126208

    // Seatalk mode bytes [12]
    private const val MODE_STANDBY: Byte = 0x00
    private const val MODE_AUTO: Byte = 0x40

    // Mode qualifier bytes [13]
    private const val QUAL_STANDBY: Byte = 0x00
    private const val QUAL_WIND: Byte = 0x01

    // Wind submode bytes [15]-[16]
    private const val WIND_SUB_DEFAULT_HI: Byte = 0xFF.toByte()  // keep current submode
    private const val WIND_SUB_DEFAULT_LO: Byte = 0xFF.toByte()
    private const val WIND_SUB_AWA_HI: Byte = 0x03
    private const val WIND_SUB_AWA_LO: Byte = 0x00
    private const val WIND_SUB_TWA_HI: Byte = 0x04
    private const val WIND_SUB_TWA_LO: Byte = 0x00

    // Common command header fragments
    private val MODE_CMD_PREFIX = byteArrayOf(
        0x01,                           // FC = Command
        0x63, 0xFF.toByte(), 0x00,      // Target PGN 65379 (LE)
        0xF8.toByte(),                  // Priority + reserved
        0x04,                           // Number of parameter pairs
        0x01, 0x3B, 0x07, 0x03, 0x04, 0x04  // Param indices + mfr header
    )

    private val HEADING_CMD_PREFIX = byteArrayOf(
        0x01,                           // FC = Command
        0x50, 0xFF.toByte(), 0x00,      // Target PGN 65360 (LE)
        0xF8.toByte(),                  // Priority + reserved
        0x03,                           // Number of parameter pairs
        0x01, 0x3B, 0x07, 0x03, 0x04, 0x06  // Param indices + mfr header
    )

    private val WIND_CMD_PREFIX = byteArrayOf(
        0x01,                           // FC = Command
        0x41, 0xFF.toByte(), 0x00,      // Target PGN 65345 (LE)
        0xF8.toByte(),                  // Priority + reserved
        0x03,                           // Number of parameter pairs
        0x01, 0x3B, 0x07, 0x03, 0x04, 0x04  // Param indices + mfr header (index 0x04)
    )

    private fun modeSuffix(subHi: Byte, subLo: Byte) =
        byteArrayOf(0x05, subHi, subLo)

    fun buildStandbyCommand(): ByteArray {
        return MODE_CMD_PREFIX +
            byteArrayOf(MODE_STANDBY, QUAL_STANDBY) +
            modeSuffix(WIND_SUB_DEFAULT_HI, WIND_SUB_DEFAULT_LO)
    }

    fun buildAutoCompassCommand(): ByteArray {
        return MODE_CMD_PREFIX +
            byteArrayOf(MODE_AUTO, QUAL_STANDBY) +
            modeSuffix(WIND_SUB_DEFAULT_HI, WIND_SUB_DEFAULT_LO)
    }

    fun buildWindAwaCommand(): ByteArray {
        return MODE_CMD_PREFIX +
            byteArrayOf(MODE_STANDBY, QUAL_WIND) +
            modeSuffix(WIND_SUB_AWA_HI, WIND_SUB_AWA_LO)
    }

    fun buildWindTwaCommand(): ByteArray {
        return MODE_CMD_PREFIX +
            byteArrayOf(MODE_STANDBY, QUAL_WIND) +
            modeSuffix(WIND_SUB_TWA_HI, WIND_SUB_TWA_LO)
    }

    fun buildHeadingSet(headingDegrees: Double): ByteArray {
        val encoded = encodeAngle(headingDegrees)
        return HEADING_CMD_PREFIX + encoded
    }

    fun buildWindDatumSet(windAngleDegrees: Double): ByteArray {
        // Convert signed degrees (-180..180) to 0..360 for encoding
        var deg = windAngleDegrees
        if (deg < 0) deg += 360.0
        val encoded = encodeAngle(deg)
        return WIND_CMD_PREFIX + encoded
    }

    /**
     * Encode an angle in degrees to the Raymarine format: radians * 10000 as uint16 LE.
     */
    fun encodeAngle(degrees: Double): ByteArray {
        val raw = (degrees * Math.PI / 180.0 * 10000.0).toInt() and 0xFFFF
        return byteArrayOf(
            (raw and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte(),
        )
    }

    /**
     * Decode a uint16 LE angle (radians * 10000) back to degrees.
     */
    fun decodeAngle(lo: Byte, hi: Byte): Double {
        val raw = (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)
        return raw / 10000.0 * (180.0 / Math.PI)
    }
}
