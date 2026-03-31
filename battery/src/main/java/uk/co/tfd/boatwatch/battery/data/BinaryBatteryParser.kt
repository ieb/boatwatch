package uk.co.tfd.boatwatch.battery.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the compact binary BLE battery format.
 *
 * Layout (little-endian):
 *   [0]     U8   magic         0xBB
 *   [1..2]  U16  pack_voltage  0.01V
 *   [3..4]  S16  current       0.01A
 *   [5..6]  U16  remaining_ah  0.01Ah
 *   [7..8]  U16  full_ah       0.01Ah
 *   [9]     U8   soc           %
 *   [10..11] U16 cycles
 *   [12..13] U16 errors        bitmap
 *   [14]    U8   fet_status    bitmap
 *   [15]    U8   n_cells
 *   [16..]  U16× cell_voltages 0.001V each
 *   [..]    U8   n_ntc
 *   [..]    U16× ntc_temps     0.1K each
 */
object BinaryBatteryParser {

    const val MAGIC: Byte = 0xBB.toByte()
    private const val HEADER_SIZE = 16 // bytes before cell array

    fun parseBinary(data: ByteArray): BatteryState? {
        if (data.size < HEADER_SIZE + 1) return null // minimum: header + n_ntc byte
        if (data[0] != MAGIC) return null

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // skip magic

        val packV = buf.short.toInt() and 0xFFFF
        val current = buf.short.toInt()
        val remainingAh = buf.short.toInt() and 0xFFFF
        val fullAh = buf.short.toInt() and 0xFFFF
        val soc = buf.get().toInt() and 0xFF
        val cycles = buf.short.toInt() and 0xFFFF
        val errors = buf.short.toInt() and 0xFFFF
        val fetStatus = buf.get().toInt() and 0xFF
        val nCells = buf.get().toInt() and 0xFF

        if (buf.remaining() < nCells * 2 + 1) return null

        val cellVoltages = (0 until nCells).map {
            (buf.short.toInt() and 0xFFFF) * 0.001
        }

        val nNtc = buf.get().toInt() and 0xFF
        if (buf.remaining() < nNtc * 2) return null

        val temperatures = (0 until nNtc).map {
            (buf.short.toInt() and 0xFFFF) * 0.1 - 273.15
        }

        return BatteryState(
            packVoltage = packV * 0.01,
            current = current * 0.01,
            stateOfCharge = soc,
            remainingAh = remainingAh * 0.01,
            fullCapacityAh = fullAh * 0.01,
            chargeCycles = cycles,
            errors = errors,
            fetStatus = fetStatus,
            cellVoltages = cellVoltages,
            temperatures = temperatures,
            lastUpdated = System.currentTimeMillis(),
        )
    }
}
