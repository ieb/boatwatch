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

            while (isActive) {
                simHeading = (simHeading + 0.2) % 360.0
                pushState()
                delay(200)
            }
        }
    }

    override fun disconnect() {
        updateJob?.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
        _state.value = AutopilotState()
    }

    override suspend fun sendBinaryCommand(data: ByteArray): Boolean {
        if (data.size < 2 || data[0] != BinaryAutopilotProtocol.MAGIC) return false
        val cmd = data[1].toInt() and 0xFF

        when (cmd) {
            0x01 -> { simMode = PilotMode.STANDBY; Log.d(TAG, "STANDBY") }
            0x02 -> {
                simMode = PilotMode.COMPASS
                simTargetHeading = simHeading
                Log.d(TAG, "COMPASS")
            }
            0x03 -> { simMode = PilotMode.WIND_AWA; Log.d(TAG, "WIND_AWA") }
            0x04 -> { simMode = PilotMode.WIND_TWA; Log.d(TAG, "WIND_TWA") }
            0x10 -> if (data.size >= 4) {
                val raw = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                simTargetHeading = raw * 0.01
            }
            0x11 -> if (data.size >= 4) {
                val raw = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                simTargetWind = raw.toShort() * 0.01
            }
            0x20 -> if (data.size >= 4) {
                val raw = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                simTargetHeading = (simTargetHeading + raw.toShort() * 0.01 + 360.0) % 360.0
            }
            0x21 -> if (data.size >= 4) {
                val raw = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                simTargetWind += raw.toShort() * 0.01
                while (simTargetWind > 180.0) simTargetWind -= 360.0
                while (simTargetWind < -180.0) simTargetWind += 360.0
            }
            else -> return false
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
