package uk.co.tfd.boatwatch.battery.data

/**
 * Parses the B-line from the /api/store endpoint.
 *
 * Expected format:
 * B,<packV>,<current>,<remainingAh>,<fullAh>,<soc>,<cycles>,<errors>,<fetStatus>,
 *   <nCells>,<cell0V>,...,<cellNV>,<nNTC>,<ntc0_K>,...,<ntcN_K>
 *
 * Units: packV in 0.01V, current in 0.01A signed, remainingAh/fullAh in 0.01Ah,
 *        cell voltages in 0.001V, temperatures in 0.1K.
 */
object StoreApiParser {

    fun parseBLine(line: String): BatteryState? {
        val parts = line.split(",")
        if (parts.isEmpty() || parts[0] != "B") return null
        if (parts.size < 10) return null

        return try {
            val packV = parts[1].toDouble() * 0.01
            val current = parts[2].toDouble() * 0.01
            val remainingAh = parts[3].toDouble() * 0.01
            val fullAh = parts[4].toDouble() * 0.01
            val soc = parts[5].toInt()
            val cycles = parts[6].toInt()
            val errors = parts[7].toInt()
            val fetStatus = parts[8].toInt()

            val nCells = parts[9].toInt()
            val cellStart = 10
            if (parts.size < cellStart + nCells + 1) return null

            val cellVoltages = (0 until nCells).map { i ->
                parts[cellStart + i].toDouble() * 0.001
            }

            val ntcIdx = cellStart + nCells
            val nNTC = parts[ntcIdx].toInt()
            if (parts.size < ntcIdx + 1 + nNTC) return null

            val temperatures = (0 until nNTC).map { i ->
                parts[ntcIdx + 1 + i].toDouble() * 0.1 - 273.15
            }

            BatteryState(
                packVoltage = packV,
                current = current,
                stateOfCharge = soc,
                remainingAh = remainingAh,
                fullCapacityAh = fullAh,
                chargeCycles = cycles,
                errors = errors,
                fetStatus = fetStatus,
                cellVoltages = cellVoltages,
                temperatures = temperatures,
                lastUpdated = System.currentTimeMillis(),
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun parseStoreResponse(response: String): BatteryState? {
        return response.lineSequence()
            .firstOrNull { it.startsWith("B,") }
            ?.let { parseBLine(it) }
    }
}
