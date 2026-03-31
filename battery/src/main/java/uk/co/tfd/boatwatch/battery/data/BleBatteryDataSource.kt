package uk.co.tfd.boatwatch.battery.data

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class BleBatteryDataSource(
    private val context: Context,
    private val deviceAddress: String,
) : BatteryDataSource {

    companion object {
        private const val TAG = "BleBattery"
        private const val RECONNECT_DELAY_MS = 3000L
        val SERVICE_UUID: UUID = UUID.fromString("0000AA00-0000-1000-8000-00805f9b34fb")
        val STORE_NOTIFY_UUID: UUID = UUID.fromString("0000AA03-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val _state = MutableStateFlow(BatteryState())
    override val state: StateFlow<BatteryState> = _state

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    @Volatile private var destroyed = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (destroyed) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, requesting MTU 512")
                    _connectionStatus.value = ConnectionStatus.CONNECTING
                    g.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status=$status)")
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    g.close()
                    gatt = null
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
                _connectionStatus.value = ConnectionStatus.ERROR
                return
            }

            // Enable notifications on Store characteristic
            val notifyChar = service.getCharacteristic(STORE_NOTIFY_UUID)
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true)
                val desc = notifyChar.getDescriptor(CCC_DESCRIPTOR_UUID)
                if (desc != null) {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                }
            }

            _connectionStatus.value = ConnectionStatus.CONNECTED
            Log.i(TAG, "BLE ready — notifications enabled")
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            processBytes(bytes)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            processBytes(value)
        }
    }

    private fun processBytes(data: ByteArray) {
        val parsed = BinaryBatteryParser.parseBinary(data) ?: return
        _state.value = parsed
    }

    override fun start(serverUrl: String) {
        // serverUrl is ignored for BLE — we use the deviceAddress
        destroyed = false
        doConnect()
    }

    private fun doConnect() {
        if (destroyed) return
        _connectionStatus.value = ConnectionStatus.CONNECTING

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter not available or disabled")
            _connectionStatus.value = ConnectionStatus.ERROR
            return
        }

        try {
            val device = adapter.getRemoteDevice(deviceAddress)
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "connectGatt failed: ${e.message}")
            _connectionStatus.value = ConnectionStatus.ERROR
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        handler.postDelayed({ doConnect() }, RECONNECT_DELAY_MS)
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _state.value = BatteryState()
    }

    override fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }
}
