package uk.co.tfd.boatwatch.battery.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.tfd.boatwatch.battery.data.*

class BatteryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS = "battery_prefs"
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

    private var dataSource: BatteryDataSource = createDataSource()

    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    init {
        startDataSource()
    }

    private fun createDataSource(): BatteryDataSource {
        return if (_demoMode.value) {
            FakeBatteryDataSource()
        } else if (_bleDeviceAddress.value != null) {
            BleBatteryDataSource(getApplication(), _bleDeviceAddress.value!!, _blePin.value)
        } else {
            FakeBatteryDataSource() // No device configured yet
        }
    }

    private fun startDataSource() {
        dataSource.start()
        forwardState()
    }

    @Volatile private var forwardThread: Thread? = null

    private fun forwardState() {
        forwardThread?.interrupt()
        val ds = dataSource
        forwardThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    _state.value = ds.state.value
                    _connectionStatus.value = ds.connectionStatus.value
                    Thread.sleep(100)
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
    }

    private fun reconnect() {
        dataSource.destroy()
        dataSource = createDataSource()
        _state.value = BatteryState()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        startDataSource()
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

    override fun onCleared() {
        super.onCleared()
        dataSource.destroy()
    }
}
