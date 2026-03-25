package uk.co.tfd.boatwatch.autopilot.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import uk.co.tfd.boatwatch.autopilot.protocol.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class HttpAutopilotClient : AutopilotHttpClient {

    companion object {
        private const val TAG = "HttpAutopilot"
        private const val RECONNECT_DELAY_MS = 3000L
        private val STATUS_PGNS = "65379,65359,65360,65345"
    }

    private val _state = MutableStateFlow(AutopilotState())
    override val state: StateFlow<AutopilotState> = _state

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null
    private var baseUrl: String = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // no read timeout for streaming
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val postClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    override fun connect(baseUrl: String) {
        this.baseUrl = baseUrl.trimEnd('/')
        streamJob?.cancel()
        _connectionState.value = ConnectionState.CONNECTING

        streamJob = scope.launch {
            while (isActive) {
                try {
                    streamState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stream error: ${e.message}")
                    _connectionState.value = ConnectionState.ERROR
                }
                // Reconnect after delay
                if (isActive) {
                    delay(RECONNECT_DELAY_MS)
                    _connectionState.value = ConnectionState.CONNECTING
                }
            }
        }
    }

    override fun disconnect() {
        streamJob?.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
        _state.value = AutopilotState()
    }

    override suspend fun sendCommand(seaSmartSentence: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder()
                    .add("msg", seaSmartSentence)
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/api/seasmart")
                    .post(body)
                    .build()
                val response = postClient.newCall(request).execute()
                val success = response.isSuccessful
                response.close()
                if (!success) {
                    Log.w(TAG, "Command failed: HTTP ${response.code}")
                }
                success
            } catch (e: Exception) {
                Log.w(TAG, "Send failed: ${e.message}")
                false
            }
        }
    }

    override fun destroy() {
        streamJob?.cancel()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        postClient.dispatcher.executorService.shutdown()
    }

    private suspend fun streamState() {
        val url = "$baseUrl/api/seasmart?pgns=$STATUS_PGNS"
        val request = Request.Builder().url(url).get().build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty response body")
        _connectionState.value = ConnectionState.CONNECTED

        try {
            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            var line: String?
            while (currentCoroutineContext().isActive) {
                line = reader.readLine() ?: break
                if (line.isBlank()) continue

                val frame = SeaSmartCodec.decode(line) ?: continue
                _state.value = RaymarineState.applyFrame(frame, _state.value)
            }
        } finally {
            response.close()
        }
    }
}
