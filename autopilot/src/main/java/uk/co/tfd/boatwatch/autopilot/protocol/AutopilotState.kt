package uk.co.tfd.boatwatch.autopilot.protocol

data class AutopilotState(
    val pilotMode: PilotMode = PilotMode.STANDBY,
    val currentHeading: Double = 0.0,
    val targetHeading: Double = 0.0,
    val targetWindAngle: Double = 0.0,
    val lastUpdateMs: Long = 0,
) {
    val targetDisplayValue: Double
        get() = when (pilotMode) {
            PilotMode.STANDBY -> 0.0
            PilotMode.COMPASS -> targetHeading
            PilotMode.WIND_AWA, PilotMode.WIND_TWA -> targetWindAngle
        }
}

enum class PilotMode {
    STANDBY, COMPASS, WIND_AWA, WIND_TWA;

    val displayName: String
        get() = when (this) {
            STANDBY -> "STANDBY"
            COMPASS -> "HDG"
            WIND_AWA -> "AWA"
            WIND_TWA -> "TWA"
        }
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
