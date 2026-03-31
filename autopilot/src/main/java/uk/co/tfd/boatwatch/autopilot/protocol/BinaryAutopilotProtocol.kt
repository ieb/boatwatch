package uk.co.tfd.boatwatch.autopilot.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compact binary BLE protocol for autopilot state and commands.
 *
 * State (10 bytes, firmware → watch):
 *   [0]    U8   magic           0xAA
 *   [1]    U8   mode            0=STANDBY, 1=COMPASS, 2=WIND_AWA, 3=WIND_TWA
 *   [2..3] U16  current_heading 0.01°
 *   [4..5] U16  target_heading  0.01°
 *   [6..7] S16  target_wind     0.01° (±180°)
 *   [8..9] U16  reserved
 *
 * Commands (2–4 bytes, watch → firmware):
 *   [0]    U8   magic           0xAA
 *   [1]    U8   command_id
 *   [2..3] S16/U16 payload      (for set/adjust commands)
 */
object BinaryAutopilotProtocol {

    const val MAGIC: Byte = 0xAA.toByte()

    // Command IDs
    private const val CMD_STANDBY: Byte = 0x01
    private const val CMD_COMPASS: Byte = 0x02
    private const val CMD_WIND_AWA: Byte = 0x03
    private const val CMD_WIND_TWA: Byte = 0x04
    private const val CMD_SET_HEADING: Byte = 0x10
    private const val CMD_SET_WIND: Byte = 0x11
    private const val CMD_ADJUST_HEADING: Byte = 0x20
    private const val CMD_ADJUST_WIND: Byte = 0x21

    private val MODE_MAP = arrayOf(PilotMode.STANDBY, PilotMode.COMPASS, PilotMode.WIND_AWA, PilotMode.WIND_TWA)

    fun parseState(data: ByteArray): AutopilotState? {
        if (data.size < 10 || data[0] != MAGIC) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // skip magic

        val modeIdx = buf.get().toInt() and 0xFF
        val mode = if (modeIdx < MODE_MAP.size) MODE_MAP[modeIdx] else PilotMode.STANDBY

        val currentHeading = (buf.short.toInt() and 0xFFFF) * 0.01
        val targetHeading = (buf.short.toInt() and 0xFFFF) * 0.01
        val targetWind = buf.short.toInt() * 0.01

        return AutopilotState(
            pilotMode = mode,
            currentHeading = currentHeading,
            targetHeading = targetHeading,
            targetWindAngle = targetWind,
            lastUpdateMs = System.currentTimeMillis(),
        )
    }

    fun cmdStandby(): ByteArray = byteArrayOf(MAGIC, CMD_STANDBY)
    fun cmdCompass(): ByteArray = byteArrayOf(MAGIC, CMD_COMPASS)
    fun cmdWindAwa(): ByteArray = byteArrayOf(MAGIC, CMD_WIND_AWA)
    fun cmdWindTwa(): ByteArray = byteArrayOf(MAGIC, CMD_WIND_TWA)

    fun cmdSetHeading(degrees: Double): ByteArray {
        val raw = (degrees % 360.0 * 100).toInt().coerceIn(0, 36000)
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC).put(CMD_SET_HEADING).putShort(raw.toShort())
            .array()
    }

    fun cmdSetWind(degrees: Double): ByteArray {
        val raw = (degrees * 100).toInt().coerceIn(-18000, 18000)
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC).put(CMD_SET_WIND).putShort(raw.toShort())
            .array()
    }

    fun cmdAdjustHeading(deltaDegrees: Double): ByteArray {
        val raw = (deltaDegrees * 100).toInt().coerceIn(-18000, 18000)
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC).put(CMD_ADJUST_HEADING).putShort(raw.toShort())
            .array()
    }

    fun cmdAdjustWind(deltaDegrees: Double): ByteArray {
        val raw = (deltaDegrees * 100).toInt().coerceIn(-18000, 18000)
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC).put(CMD_ADJUST_WIND).putShort(raw.toShort())
            .array()
    }
}
