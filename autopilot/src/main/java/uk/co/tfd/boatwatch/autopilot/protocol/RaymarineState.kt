package uk.co.tfd.boatwatch.autopilot.protocol

/**
 * Parses incoming Raymarine Seatalk PGN data from SeaSmart frames.
 */
object RaymarineState {

    private const val RAYMARINE_MFR_LO: Byte = 0x3B
    private const val RAYMARINE_MFR_HI: Byte = 0x9F.toByte()

    private fun isRaymarine(data: ByteArray): Boolean {
        return data.size >= 2 && data[0] == RAYMARINE_MFR_LO && data[1] == RAYMARINE_MFR_HI
    }

    /**
     * Parse PGN 65379 — Pilot Mode.
     * Data: [3B 9F 01 MODE 00 SUBMODE 00 00 FF]
     */
    fun parsePilotMode(data: ByteArray): PilotMode? {
        if (!isRaymarine(data) || data.size < 6) return null
        val mode = data[3].toInt() and 0xFF
        val submode = data[5].toInt() and 0xFF

        return when {
            mode == 0x00 && submode == 0x00 -> PilotMode.STANDBY
            mode == 0x40 && submode == 0x00 -> PilotMode.COMPASS
            mode == 0x00 && submode == 0x01 -> PilotMode.WIND_AWA
            // TWA is not directly distinguishable from AWA in the Seatalk protocol;
            // both use submode 0x01. We default to AWA.
            else -> null
        }
    }

    /**
     * Parse PGN 65359 — Current Heading.
     * Data: [3B 9F SID FF FF HEADING_LO HEADING_HI FF]
     */
    fun parseCurrentHeading(data: ByteArray): Double? {
        if (!isRaymarine(data) || data.size < 7) return null
        // Heading magnetic at bytes 5-6
        val lo = data[5]
        val hi = data[6]
        if (lo.toInt() == 0xFF.toByte().toInt() && hi.toInt() == 0xFF.toByte().toInt()) return null
        return RaymarineN2K.decodeAngle(lo, hi)
    }

    /**
     * Parse PGN 65360 — Target/Locked Heading.
     * Data: [3B 9F SID FF FF HEADING_LO HEADING_HI FF]
     */
    fun parseLockedHeading(data: ByteArray): Double? {
        if (!isRaymarine(data) || data.size < 7) return null
        val lo = data[5]
        val hi = data[6]
        if (lo.toInt() == 0xFF.toByte().toInt() && hi.toInt() == 0xFF.toByte().toInt()) return null
        return RaymarineN2K.decodeAngle(lo, hi)
    }

    /**
     * Parse PGN 65345 — Wind Datum Target.
     * Data: [3B 9F DATUM_LO DATUM_HI ROLLING_LO ROLLING_HI FF FF]
     */
    fun parseWindDatum(data: ByteArray): Double? {
        if (!isRaymarine(data) || data.size < 4) return null
        val lo = data[2]
        val hi = data[3]
        if (lo.toInt() == 0xFF.toByte().toInt() && hi.toInt() == 0xFF.toByte().toInt()) return null
        var degrees = RaymarineN2K.decodeAngle(lo, hi)
        // Convert from 0-360 to signed (positive = starboard, negative = port)
        if (degrees > 180.0) degrees -= 360.0
        return degrees
    }

    /**
     * Process a SeaSmart frame and update autopilot state.
     */
    fun applyFrame(frame: SeaSmartCodec.Frame, current: AutopilotState): AutopilotState {
        val now = System.currentTimeMillis()
        return when (frame.pgn) {
            65379L -> {
                val mode = parsePilotMode(frame.data)
                if (mode != null) current.copy(pilotMode = mode, lastUpdateMs = now)
                else current
            }
            65359L -> {
                val heading = parseCurrentHeading(frame.data)
                if (heading != null) current.copy(currentHeading = heading, lastUpdateMs = now)
                else current
            }
            65360L -> {
                val target = parseLockedHeading(frame.data)
                if (target != null) current.copy(targetHeading = target, lastUpdateMs = now)
                else current
            }
            65345L -> {
                val wind = parseWindDatum(frame.data)
                if (wind != null) current.copy(targetWindAngle = wind, lastUpdateMs = now)
                else current
            }
            else -> current
        }
    }
}
