package uk.co.tfd.boatwatch.battery.data

import kotlinx.coroutines.flow.StateFlow

interface BatteryDataSource {
    val state: StateFlow<BatteryState>
    val connectionStatus: StateFlow<ConnectionStatus>

    fun start(serverUrl: String)
    fun stop()
    fun destroy()
}
