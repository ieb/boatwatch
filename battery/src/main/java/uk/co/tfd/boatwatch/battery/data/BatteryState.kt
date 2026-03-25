package uk.co.tfd.boatwatch.battery.data

data class BatteryState(
    val packVoltage: Double = Double.NaN,
    val current: Double = Double.NaN,
    val stateOfCharge: Int = -1,
    val remainingAh: Double = Double.NaN,
    val fullCapacityAh: Double = Double.NaN,
    val chargeCycles: Int = 0,
    val errors: Int = 0,
    val fetStatus: Int = 0,
    val cellVoltages: List<Double> = emptyList(),
    val temperatures: List<Double> = emptyList(),
    val lastUpdated: Long = 0L,
) {
    val isCharging: Boolean get() = current > 0.05
    val isDischarging: Boolean get() = current < -0.05

    val healthPercent: Int
        get() = if (chargeCycles >= 0) {
            ((8000 - chargeCycles).coerceIn(0, 8000) * 100 / 8000)
        } else -1

    val hasError: Boolean get() = errors != 0

    val cellImbalance: Double
        get() = if (cellVoltages.size >= 2) {
            cellVoltages.max() - cellVoltages.min()
        } else 0.0
}

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
