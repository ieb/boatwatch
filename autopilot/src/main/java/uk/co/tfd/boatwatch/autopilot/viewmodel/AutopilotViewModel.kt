package uk.co.tfd.boatwatch.autopilot.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.tfd.boatwatch.autopilot.BuildConfig
import uk.co.tfd.boatwatch.autopilot.network.*
import uk.co.tfd.boatwatch.autopilot.protocol.*

class AutopilotViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS = "autopilot_prefs"
        private const val KEY_URL = "server_url"
        private const val KEY_URL_HISTORY = "url_history"
        private const val DEFAULT_URL = BuildConfig.DEFAULT_URL
        private const val SOURCE_ADDRESS = 0x42
        private const val MAX_HISTORY = 5
    }

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client: AutopilotHttpClient = if (BuildConfig.FAKE_HTTP) {
        FakeAutopilotClient()
    } else {
        HttpAutopilotClient()
    }

    // The Seatalk protocol uses the same wind command (mode=0x00, submode=0x01) for
    // both AWA and TWA. We track the user's chosen wind sub-mode locally and apply it
    // when the firmware reports back wind mode.
    private var requestedWindMode = PilotMode.WIND_AWA

    private val _displayState = MutableStateFlow(AutopilotState())
    val state: StateFlow<AutopilotState> = _displayState

    val connectionState: StateFlow<ConnectionState> = client.connectionState

    val serverUrl: MutableStateFlow<String> = MutableStateFlow(
        prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
    )

    private val _urlHistory = MutableStateFlow(loadUrlHistory())
    val urlHistory: StateFlow<List<String>> = _urlHistory

    init {
        client.connect(serverUrl.value)
        viewModelScope.launch {
            client.state.collect { raw ->
                // When firmware reports WIND_AWA (it can't distinguish AWA/TWA),
                // substitute our locally tracked wind mode
                _displayState.value = if (raw.pilotMode == PilotMode.WIND_AWA) {
                    raw.copy(pilotMode = requestedWindMode)
                } else {
                    // If firmware says standby or compass, reset wind mode to AWA
                    if (raw.pilotMode == PilotMode.STANDBY || raw.pilotMode == PilotMode.COMPASS) {
                        requestedWindMode = PilotMode.WIND_AWA
                    }
                    raw
                }
            }
        }
    }

    fun updateServerUrl(url: String) {
        serverUrl.value = url
        prefs.edit().putString(KEY_URL, url).apply()
        addToHistory(url)
        client.disconnect()
        client.connect(url)
    }

    private fun loadUrlHistory(): List<String> {
        val json = prefs.getString(KEY_URL_HISTORY, null) ?: return emptyList()
        return json.removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }

    private fun addToHistory(url: String) {
        val history = _urlHistory.value.toMutableList()
        history.remove(url)
        history.add(0, url)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        _urlHistory.value = history
        val json = history.joinToString(",") { "\"$it\"" }
        prefs.edit().putString(KEY_URL_HISTORY, "[$json]").apply()
    }

    fun standby() {
        requestedWindMode = PilotMode.WIND_AWA
        send(RaymarineN2K.buildStandbyCommand())
    }

    fun cycleMode() {
        val current = _displayState.value.pilotMode
        when (current) {
            PilotMode.STANDBY -> {
                send(RaymarineN2K.buildAutoCompassCommand())
            }
            PilotMode.COMPASS -> {
                requestedWindMode = PilotMode.WIND_AWA
                send(RaymarineN2K.buildWindAwaCommand())
            }
            PilotMode.WIND_AWA -> {
                requestedWindMode = PilotMode.WIND_TWA
                send(RaymarineN2K.buildWindTwaCommand())
            }
            PilotMode.WIND_TWA -> {
                send(RaymarineN2K.buildAutoCompassCommand())
            }
        }
    }

    fun adjustTarget(deltaDegrees: Double) {
        val current = state.value
        if (current.pilotMode == PilotMode.STANDBY) return

        val cmd = when (current.pilotMode) {
            PilotMode.COMPASS -> {
                var newHeading = current.targetHeading + deltaDegrees
                while (newHeading < 0) newHeading += 360.0
                while (newHeading >= 360.0) newHeading -= 360.0
                RaymarineN2K.buildHeadingSet(newHeading)
            }
            PilotMode.WIND_AWA, PilotMode.WIND_TWA -> {
                // +1 = turn starboard = wind moves to port (negative), so negate
                var newWind = current.targetWindAngle - deltaDegrees
                while (newWind > 180.0) newWind -= 360.0
                while (newWind < -180.0) newWind += 360.0
                RaymarineN2K.buildWindDatumSet(newWind)
            }
            else -> return
        }
        send(cmd)
    }

    private fun send(commandData: ByteArray) {
        val sentence = SeaSmartCodec.encode(RaymarineN2K.PGN_COMMAND, SOURCE_ADDRESS, commandData)
        viewModelScope.launch {
            client.sendCommand(sentence)
        }
    }

    override fun onCleared() {
        super.onCleared()
        client.destroy()
    }
}
