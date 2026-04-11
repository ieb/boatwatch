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
        private const val KEY_DEMO_MODE = "demo_mode"
        private const val KEY_BLE_DEVICE_ADDRESS = "ble_device_address"
        private const val KEY_BLE_DEVICE_NAME = "ble_device_name"
        private const val KEY_BLE_PIN = "ble_pin"
    }

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _demoMode = MutableStateFlow(prefs.getBoolean(KEY_DEMO_MODE, false))
    val demoMode: StateFlow<Boolean> = _demoMode

    private val _bleDeviceAddress = MutableStateFlow(prefs.getString(KEY_BLE_DEVICE_ADDRESS, null))
    val bleDeviceAddress: StateFlow<String?> = _bleDeviceAddress

    private val _bleDeviceName = MutableStateFlow(prefs.getString(KEY_BLE_DEVICE_NAME, null))
    val bleDeviceName: StateFlow<String?> = _bleDeviceName

    private val _blePin = MutableStateFlow(prefs.getString(KEY_BLE_PIN, "0000") ?: "0000")
    val blePin: StateFlow<String> = _blePin

    private var client: AutopilotHttpClient = createClient()
    private var collectJob: Job? = null

    private val _displayState = MutableStateFlow(AutopilotState())
    val state: StateFlow<AutopilotState> = _displayState

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    init {
        startClient()
    }

    private fun createClient(): AutopilotHttpClient {
        return if (_demoMode.value) {
            FakeAutopilotClient()
        } else if (_bleDeviceAddress.value != null) {
            BleAutopilotClient(getApplication(), _bleDeviceAddress.value!!, _blePin.value)
        } else {
            FakeAutopilotClient() // No device configured yet
        }
    }

    private fun startClient() {
        collectJob?.cancel()
        client.connect("")

        collectJob = viewModelScope.launch {
            launch {
                client.connectionState.collect { _connectionState.value = it }
            }
            launch {
                client.state.collect { raw ->
                    _displayState.value = raw
                }
            }
        }
    }

    private fun reconnect() {
        collectJob?.cancel()
        client.destroy()
        client = createClient()
        _displayState.value = AutopilotState()
        startClient()
    }

    fun setDemoMode(enabled: Boolean) {
        if (enabled == _demoMode.value) return
        _demoMode.value = enabled
        prefs.edit().putBoolean(KEY_DEMO_MODE, enabled).apply()
        reconnect()
    }

    fun selectBleDevice(address: String, name: String) {
        _bleDeviceAddress.value = address
        _bleDeviceName.value = name
        prefs.edit()
            .putString(KEY_BLE_DEVICE_ADDRESS, address)
            .putString(KEY_BLE_DEVICE_NAME, name)
            .apply()
        if (!_demoMode.value) reconnect()
    }

    fun setBlePin(pin: String) {
        _blePin.value = pin
        prefs.edit().putString(KEY_BLE_PIN, pin).apply()
    }

    fun standby() {
        sendBinary(BinaryAutopilotProtocol.cmdStandby())
    }

    fun cycleMode() {
        when (_displayState.value.pilotMode) {
            PilotMode.STANDBY -> sendBinary(BinaryAutopilotProtocol.cmdCompass())
            PilotMode.COMPASS -> sendBinary(BinaryAutopilotProtocol.cmdWindAwa())
            PilotMode.WIND_AWA -> sendBinary(BinaryAutopilotProtocol.cmdWindTwa())
            PilotMode.WIND_TWA -> sendBinary(BinaryAutopilotProtocol.cmdCompass())
        }
    }

    fun adjustTarget(deltaDegrees: Double) {
        val current = state.value
        if (current.pilotMode == PilotMode.STANDBY) return

        when (current.pilotMode) {
            PilotMode.COMPASS -> sendBinary(BinaryAutopilotProtocol.cmdAdjustHeading(deltaDegrees))
            PilotMode.WIND_AWA, PilotMode.WIND_TWA ->
                sendBinary(BinaryAutopilotProtocol.cmdAdjustWind(-deltaDegrees))
            else -> return
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
