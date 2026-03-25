package uk.co.tfd.boatwatch.battery.data

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection
import java.net.URL

class HttpBatteryDataSource : BatteryDataSource {

    companion object {
        private const val TAG = "HttpBattery"
        private const val POLL_INTERVAL_MS = 5000L
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 5000
    }

    private val _state = MutableStateFlow(BatteryState())
    override val state: StateFlow<BatteryState> = _state

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    override fun start(serverUrl: String) {
        pollJob?.cancel()
        _connectionStatus.value = ConnectionStatus.CONNECTING

        val storeUrl = "${serverUrl.trimEnd('/')}/api/store"

        pollJob = scope.launch {
            while (isActive) {
                try {
                    val response = fetch(storeUrl)
                    val parsed = StoreApiParser.parseStoreResponse(response)
                    if (parsed != null) {
                        _state.value = parsed
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                    } else {
                        // Got a response but no B-line — firmware may not have the B-line yet
                        if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                            _connectionStatus.value = ConnectionStatus.CONNECTED
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Poll failed: ${e.message}")
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    override fun destroy() {
        pollJob?.cancel()
        scope.cancel()
    }

    private fun fetch(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
