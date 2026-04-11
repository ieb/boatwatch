package uk.co.tfd.boatwatch.autopilot.network

import kotlinx.coroutines.flow.StateFlow
import uk.co.tfd.boatwatch.autopilot.protocol.AutopilotState
import uk.co.tfd.boatwatch.autopilot.protocol.ConnectionState

interface AutopilotHttpClient {
    val state: StateFlow<AutopilotState>
    val connectionState: StateFlow<ConnectionState>

    fun connect(baseUrl: String)
    fun disconnect()
    suspend fun sendBinaryCommand(data: ByteArray): Boolean
    fun destroy()
}
