package uk.co.tfd.boatwatch.battery.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

class FakeBatteryDataSource : BatteryDataSource {

    private val _state = MutableStateFlow(BatteryState())
    override val state: StateFlow<BatteryState> = _state

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollJob: Job? = null

    override fun start(serverUrl: String) {
        pollJob?.cancel()
        _connectionStatus.value = ConnectionStatus.CONNECTING

        pollJob = scope.launch {
            delay(300)
            _connectionStatus.value = ConnectionStatus.CONNECTED

            var tick = 0
            while (isActive) {
                val baseV = 3.30
                _state.value = BatteryState(
                    packVoltage = 13.20 + Random.nextDouble(-0.05, 0.05),
                    current = -5.2 + Random.nextDouble(-0.3, 0.3),
                    stateOfCharge = (75 - tick / 60).coerceIn(0, 100),
                    remainingAh = 150.0 + Random.nextDouble(-1.0, 1.0),
                    fullCapacityAh = 200.0,
                    chargeCycles = 42,
                    errors = if (tick % 60 in 0..2 && tick >= 60) 1 else 0,
                    fetStatus = 0x03,
                    cellVoltages = listOf(
                        baseV + Random.nextDouble(-0.01, 0.01),
                        baseV + Random.nextDouble(-0.01, 0.01),
                        baseV + Random.nextDouble(-0.01, 0.01),
                        baseV - 0.02 + Random.nextDouble(-0.01, 0.01),
                    ),
                    temperatures = listOf(
                        21.0 + Random.nextDouble(-0.5, 0.5),
                        22.0 + Random.nextDouble(-0.5, 0.5),
                        20.0 + Random.nextDouble(-0.5, 0.5),
                    ),
                    lastUpdated = System.currentTimeMillis(),
                )
                tick++
                delay(2000)
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
}
