package uk.co.tfd.boatwatch.autopilot.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.tfd.boatwatch.autopilot.protocol.*

class FakeAutopilotClient : AutopilotHttpClient {

    companion object {
        private const val TAG = "FakeAutopilot"
    }

    private val _state = MutableStateFlow(AutopilotState())
    override val state: StateFlow<AutopilotState> = _state

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var updateJob: Job? = null

    private var simMode = PilotMode.STANDBY
    private var simHeading = 275.0
    private var simTargetHeading = 275.0
    private var simTargetWind = 45.0

    override fun connect(baseUrl: String) {
        _connectionState.value = ConnectionState.CONNECTING
        updateJob?.cancel()
        updateJob = scope.launch {
            delay(500)
            _connectionState.value = ConnectionState.CONNECTED
            Log.d(TAG, "Connected to fake autopilot")

            while (isActive) {
                // Drift heading slightly
                simHeading = (simHeading + 0.2) % 360.0

                _state.value = AutopilotState(
                    pilotMode = simMode,
                    currentHeading = simHeading,
                    targetHeading = simTargetHeading,
                    targetWindAngle = simTargetWind,
                    lastUpdateMs = System.currentTimeMillis(),
                )
                delay(200) // 5 Hz like real heading updates
            }
        }
    }

    override fun disconnect() {
        updateJob?.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
        _state.value = AutopilotState()
    }

    override suspend fun sendCommand(seaSmartSentence: String): Boolean {
        // Decode the SeaSmart sentence and apply to simulated state
        val frame = SeaSmartCodec.decode(seaSmartSentence) ?: return false
        if (frame.pgn != RaymarineN2K.PGN_COMMAND) return false

        val data = frame.data
        if (data.isEmpty() || data[0] != 0x01.toByte()) return false

        // Check target PGN from bytes 1-2
        val targetLo = data[1].toInt() and 0xFF
        val targetHi = data[2].toInt() and 0xFF

        when {
            // PGN 65379 mode change
            targetLo == 0x63 && targetHi == 0xFF -> {
                if (data.size >= 14) {
                    val mode = data[12].toInt() and 0xFF
                    val qualifier = data[13].toInt() and 0xFF
                    simMode = when {
                        mode == 0x00 && qualifier == 0x00 -> PilotMode.STANDBY
                        mode == 0x40 && qualifier == 0x00 -> PilotMode.COMPASS
                        mode == 0x00 && qualifier == 0x01 -> {
                            // Check wind submode bytes [15]-[16] for AWA/TWA
                            if (data.size >= 17) {
                                val subHi = data[15].toInt() and 0xFF
                                val subLo = data[16].toInt() and 0xFF
                                when {
                                    subHi == 0x04 && subLo == 0x00 -> PilotMode.WIND_TWA
                                    subHi == 0x03 && subLo == 0x00 -> PilotMode.WIND_AWA
                                    else -> PilotMode.WIND_AWA // default (0xFF 0xFF = keep last)
                                }
                            } else PilotMode.WIND_AWA
                        }
                        else -> simMode
                    }
                    if (simMode == PilotMode.COMPASS) {
                        simTargetHeading = simHeading
                    }
                    Log.d(TAG, "Mode -> $simMode")
                }
            }
            // PGN 65360 heading set
            targetLo == 0x50 && targetHi == 0xFF -> {
                if (data.size >= 14) {
                    simTargetHeading = RaymarineN2K.decodeAngle(data[12], data[13])
                    Log.d(TAG, "Target heading -> $simTargetHeading")
                }
            }
            // PGN 65345 wind datum set
            targetLo == 0x41 && targetHi == 0xFF -> {
                if (data.size >= 14) {
                    var wind = RaymarineN2K.decodeAngle(data[12], data[13])
                    if (wind > 180.0) wind -= 360.0
                    simTargetWind = wind
                    Log.d(TAG, "Target wind -> $simTargetWind")
                }
            }
        }

        pushState()
        return true
    }

    override fun destroy() {
        updateJob?.cancel()
        scope.cancel()
    }

    private fun pushState() {
        _state.value = AutopilotState(
            pilotMode = simMode,
            currentHeading = simHeading,
            targetHeading = simTargetHeading,
            targetWindAngle = simTargetWind,
            lastUpdateMs = System.currentTimeMillis(),
        )
    }
}
