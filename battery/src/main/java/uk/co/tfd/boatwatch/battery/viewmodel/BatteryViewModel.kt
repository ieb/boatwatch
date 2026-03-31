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
        private const val KEY_URL = "server_url"
        private const val KEY_URL_HISTORY = "url_history"
        private const val KEY_DEMO_MODE = "demo_mode"
        private const val KEY_TRANSPORT_MODE = "transport_mode"
        private const val KEY_BLE_DEVICE_ADDRESS = "ble_device_address"
        private const val KEY_BLE_DEVICE_NAME = "ble_device_name"
        private const val DEFAULT_URL = "http://boatsystems.local"
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

    private var dataSource: BatteryDataSource = createDataSource()

    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    val serverUrl: MutableStateFlow<String> = MutableStateFlow(
        prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
    )

    private val _urlHistory = MutableStateFlow(loadUrlHistory())
    val urlHistory: StateFlow<List<String>> = _urlHistory

    init {
        startDataSource()
    }

    private fun createDataSource(): BatteryDataSource {
        return when {
            _demoMode.value -> FakeBatteryDataSource()
            _transportMode.value == TransportMode.BLE && _bleDeviceAddress.value != null ->
                BleBatteryDataSource(getApplication(), _bleDeviceAddress.value!!)
            else -> HttpBatteryDataSource()
        }
    }

    private fun startDataSource() {
        dataSource.start(serverUrl.value)
        // Forward state — collect in a simple thread since BatteryDataSource uses StateFlow
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

    fun setDemoMode(enabled: Boolean) {
        if (enabled == _demoMode.value) return
        _demoMode.value = enabled
        prefs.edit().putBoolean(KEY_DEMO_MODE, enabled).apply()

        // Tear down old source, create new one
        dataSource.destroy()
        dataSource = createDataSource()
        _state.value = BatteryState()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        startDataSource()
    }

    fun setTransportMode(mode: TransportMode) {
        if (mode == _transportMode.value) return
        _transportMode.value = mode
        prefs.edit().putString(KEY_TRANSPORT_MODE, mode.name).apply()

        dataSource.destroy()
        dataSource = createDataSource()
        _state.value = BatteryState()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        startDataSource()
    }

    fun selectBleDevice(address: String, name: String) {
        _bleDeviceAddress.value = address
        _bleDeviceName.value = name
        prefs.edit()
            .putString(KEY_BLE_DEVICE_ADDRESS, address)
            .putString(KEY_BLE_DEVICE_NAME, name)
            .apply()

        if (_transportMode.value == TransportMode.BLE) {
            dataSource.destroy()
            dataSource = createDataSource()
            _state.value = BatteryState()
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            startDataSource()
        }
    }

    fun updateServerUrl(url: String) {
        serverUrl.value = url
        prefs.edit().putString(KEY_URL, url).apply()
        addToHistory(url)
        dataSource.stop()
        dataSource.start(url)
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

    override fun onCleared() {
        super.onCleared()
        dataSource.destroy()
    }
}
