package uk.co.tfd.boatwatch.autopilot.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.tfd.boatwatch.autopilot.network.*
import uk.co.tfd.boatwatch.autopilot.protocol.*

class AutopilotViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS = "autopilot_prefs"
        private const val KEY_URL = "server_url"
        private const val KEY_URL_HISTORY = "url_history"
        private const val KEY_DEMO_MODE = "demo_mode"
        private const val KEY_TRANSPORT_MODE = "transport_mode"
        private const val KEY_BLE_DEVICE_ADDRESS = "ble_device_address"
        private const val KEY_BLE_DEVICE_NAME = "ble_device_name"
        private const val DEFAULT_URL = "http://boatsystems.local"
        private const val SOURCE_ADDRESS = 0x42
        private const val MAX_HISTORY = 5
    }

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _demoMode = MutableStateFlow(prefs.getBoolean(KEY_DEMO_MODE, false))
    val demoMode: StateFlow<Boolean> = _demoMode

    private val _transportMode = MutableStateFlow(
        try { TransportMode.valueOf(prefs.getString(KEY_TRANSPORT_MODE, "HTTP") ?: "HTTP") }
        catch (_: Exception) { TransportMode.HTTP }
    )
    val transportMode: StateFlow<TransportMode> = _transportMode

    private val _bleDeviceAddress = MutableStateFlow(prefs.getString(KEY_BLE_DEVICE_ADDRESS, null))
    val bleDeviceAddress: StateFlow<String?> = _bleDeviceAddress

    private val _bleDeviceName = MutableStateFlow(prefs.getString(KEY_BLE_DEVICE_NAME, null))
    val bleDeviceName: StateFlow<String?> = _bleDeviceName

    private var client: AutopilotHttpClient = createClient()
    private var collectJob: Job? = null

    // The Seatalk protocol uses the same wind command (mode=0x00, submode=0x01) for
    // both AWA and TWA. We track the user's chosen wind sub-mode locally and apply it
    // when the firmware reports back wind mode.
    private var requestedWindMode = PilotMode.WIND_AWA

    private val _displayState = MutableStateFlow(AutopilotState())
    val state: StateFlow<AutopilotState> = _displayState

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    val serverUrl: MutableStateFlow<String> = MutableStateFlow(
        prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
    )

    private val _urlHistory = MutableStateFlow(loadUrlHistory())
    val urlHistory: StateFlow<List<String>> = _urlHistory

    init {
        startClient()
    }

    private fun createClient(): AutopilotHttpClient {
        return when {
            _demoMode.value -> FakeAutopilotClient()
            _transportMode.value == TransportMode.BLE && _bleDeviceAddress.value != null ->
                BleAutopilotClient(getApplication(), _bleDeviceAddress.value!!)
            else -> HttpAutopilotClient()
        }
    }

    private fun startClient() {
        collectJob?.cancel()
        client.connect(serverUrl.value)

        // Forward connection state
        collectJob = viewModelScope.launch {
            launch {
                client.connectionState.collect { _connectionState.value = it }
            }
            launch {
                client.state.collect { raw ->
                    _displayState.value = if (_transportMode.value == TransportMode.BLE) {
                        // Binary protocol sends exact mode — no override needed
                        raw
                    } else if (raw.pilotMode == PilotMode.WIND_AWA) {
                        // SeaSmart can't distinguish AWA/TWA — apply local override
                        raw.copy(pilotMode = requestedWindMode)
                    } else {
                        if (raw.pilotMode == PilotMode.STANDBY || raw.pilotMode == PilotMode.COMPASS) {
                            requestedWindMode = PilotMode.WIND_AWA
                        }
                        raw
                    }
                }
            }
        }
    }

    fun setDemoMode(enabled: Boolean) {
        if (enabled == _demoMode.value) return
        _demoMode.value = enabled
        prefs.edit().putBoolean(KEY_DEMO_MODE, enabled).apply()

        // Tear down old client, create new one
        collectJob?.cancel()
        client.destroy()
        client = createClient()
        _displayState.value = AutopilotState()
        requestedWindMode = PilotMode.WIND_AWA
        startClient()
    }

    fun setTransportMode(mode: TransportMode) {
        if (mode == _transportMode.value) return
        _transportMode.value = mode
        prefs.edit().putString(KEY_TRANSPORT_MODE, mode.name).apply()

        collectJob?.cancel()
        client.destroy()
        client = createClient()
        _displayState.value = AutopilotState()
        requestedWindMode = PilotMode.WIND_AWA
        startClient()
    }

    fun selectBleDevice(address: String, name: String) {
        _bleDeviceAddress.value = address
        _bleDeviceName.value = name
        prefs.edit()
            .putString(KEY_BLE_DEVICE_ADDRESS, address)
            .putString(KEY_BLE_DEVICE_NAME, name)
            .apply()

        // If already in BLE mode, reconnect to the new device
        if (_transportMode.value == TransportMode.BLE) {
            collectJob?.cancel()
            client.destroy()
            client = createClient()
            _displayState.value = AutopilotState()
            requestedWindMode = PilotMode.WIND_AWA
            startClient()
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
        if (_transportMode.value == TransportMode.BLE) {
            sendBinary(BinaryAutopilotProtocol.cmdStandby())
        } else {
            sendN2K(RaymarineN2K.buildStandbyCommand())
        }
    }

    fun cycleMode() {
        val current = _displayState.value.pilotMode
        if (_transportMode.value == TransportMode.BLE) {
            when (current) {
                PilotMode.STANDBY -> sendBinary(BinaryAutopilotProtocol.cmdCompass())
                PilotMode.COMPASS -> {
                    requestedWindMode = PilotMode.WIND_AWA
                    sendBinary(BinaryAutopilotProtocol.cmdWindAwa())
                }
                PilotMode.WIND_AWA -> {
                    requestedWindMode = PilotMode.WIND_TWA
                    sendBinary(BinaryAutopilotProtocol.cmdWindTwa())
                }
                PilotMode.WIND_TWA -> sendBinary(BinaryAutopilotProtocol.cmdCompass())
            }
        } else {
            when (current) {
                PilotMode.STANDBY -> sendN2K(RaymarineN2K.buildAutoCompassCommand())
                PilotMode.COMPASS -> {
                    requestedWindMode = PilotMode.WIND_AWA
                    sendN2K(RaymarineN2K.buildWindAwaCommand())
                }
                PilotMode.WIND_AWA -> {
                    requestedWindMode = PilotMode.WIND_TWA
                    sendN2K(RaymarineN2K.buildWindTwaCommand())
                }
                PilotMode.WIND_TWA -> sendN2K(RaymarineN2K.buildAutoCompassCommand())
            }
        }
    }

    fun adjustTarget(deltaDegrees: Double) {
        val current = state.value
        if (current.pilotMode == PilotMode.STANDBY) return

        if (_transportMode.value == TransportMode.BLE) {
            when (current.pilotMode) {
                PilotMode.COMPASS -> sendBinary(BinaryAutopilotProtocol.cmdAdjustHeading(deltaDegrees))
                PilotMode.WIND_AWA, PilotMode.WIND_TWA ->
                    sendBinary(BinaryAutopilotProtocol.cmdAdjustWind(-deltaDegrees))
                else -> return
            }
        } else {
            val cmd = when (current.pilotMode) {
                PilotMode.COMPASS -> {
                    var newHeading = current.targetHeading + deltaDegrees
                    while (newHeading < 0) newHeading += 360.0
                    while (newHeading >= 360.0) newHeading -= 360.0
                    RaymarineN2K.buildHeadingSet(newHeading)
                }
                PilotMode.WIND_AWA, PilotMode.WIND_TWA -> {
                    var newWind = current.targetWindAngle - deltaDegrees
                    while (newWind > 180.0) newWind -= 360.0
                    while (newWind < -180.0) newWind += 360.0
                    RaymarineN2K.buildWindDatumSet(newWind)
                }
                else -> return
            }
            sendN2K(cmd)
        }
    }

    private fun sendN2K(commandData: ByteArray) {
        val sentence = SeaSmartCodec.encode(RaymarineN2K.PGN_COMMAND, SOURCE_ADDRESS, commandData)
        viewModelScope.launch {
            client.sendCommand(sentence)
        }
    }

    private fun sendBinary(data: ByteArray) {
        viewModelScope.launch {
            client.sendBinaryCommand(data)
        }
    }

    override fun onCleared() {
        super.onCleared()
        collectJob?.cancel()
        client.destroy()
    }
}
