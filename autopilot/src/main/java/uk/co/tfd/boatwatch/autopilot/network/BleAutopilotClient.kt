package uk.co.tfd.boatwatch.autopilot.network

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.tfd.boatwatch.autopilot.protocol.*
import java.util.UUID

class BleAutopilotClient(
    private val context: Context,
    private val deviceAddress: String,
) : AutopilotHttpClient {

    companion object {
        private const val TAG = "BleAutopilot"
        private const val RECONNECT_DELAY_MS = 3000L
        val SERVICE_UUID: UUID = UUID.fromString("0000AA00-0000-1000-8000-00805f9b34fb")
        val SEASMART_NOTIFY_UUID: UUID = UUID.fromString("0000AA01-0000-1000-8000-00805f9b34fb")
        val SEASMART_COMMAND_UUID: UUID = UUID.fromString("0000AA02-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val _state = MutableStateFlow(AutopilotState())
    override val state: StateFlow<AutopilotState> = _state

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var destroyed = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (destroyed) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, requesting MTU 512")
                    _connectionState.value = ConnectionState.CONNECTING
                    g.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status=$status)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    g.close()
                    gatt = null
                    commandCharacteristic = null
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu (status=$status)")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || destroyed) return
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "BoatWatch service not found on device")
                _connectionState.value = ConnectionState.ERROR
                return
            }

            // Enable notifications on SeaSmart characteristic
            val notifyChar = service.getCharacteristic(SEASMART_NOTIFY_UUID)
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true)
                val desc = notifyChar.getDescriptor(CCC_DESCRIPTOR_UUID)
                if (desc != null) {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                }
            }

            commandCharacteristic = service.getCharacteristic(SEASMART_COMMAND_UUID)
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "BLE ready — notifications enabled")
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == SEASMART_NOTIFY_UUID) {
                val bytes = characteristic.value ?: return
                processBytes(bytes)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == SEASMART_NOTIFY_UUID) {
                processBytes(value)
            }
        }
    }

    private fun processBytes(data: ByteArray) {
        val parsed = BinaryAutopilotProtocol.parseState(data) ?: return
        _state.value = parsed
    }

    override fun connect(baseUrl: String) {
        // baseUrl is ignored for BLE — we use the deviceAddress
        destroyed = false
        doConnect()
    }

    private fun doConnect() {
        if (destroyed) return
        _connectionState.value = ConnectionState.CONNECTING

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter not available or disabled")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        try {
            val device = adapter.getRemoteDevice(deviceAddress)
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "connectGatt failed: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        handler.postDelayed({ doConnect() }, RECONNECT_DELAY_MS)
    }

    override fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _state.value = AutopilotState()
    }

    override suspend fun sendCommand(seaSmartSentence: String): Boolean = false

    override suspend fun sendBinaryCommand(data: ByteArray): Boolean {
        val char = commandCharacteristic ?: return false
        val g = gatt ?: return false
        return try {
            char.value = data
            g.writeCharacteristic(char)
        } catch (e: Exception) {
            Log.w(TAG, "BLE write failed: ${e.message}")
            false
        }
    }

    override fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        commandCharacteristic = null
    }
}
