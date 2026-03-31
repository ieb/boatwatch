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
    private val pin: String,
) : BatteryDataSource {

    companion object {
        private const val TAG = "BleBattery"
        private const val RECONNECT_DELAY_MS = 3000L
        val SERVICE_UUID: UUID = UUID.fromString("0000AA00-0000-1000-8000-00805f9b34fb")
        val COMMAND_UUID: UUID = UUID.fromString("0000AA02-0000-1000-8000-00805f9b34fb")
        val BATTERY_NOTIFY_UUID: UUID = UUID.fromString("0000AA03-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val _state = MutableStateFlow(BatteryState())
    override val state: StateFlow<BatteryState> = _state

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var destroyed = false
    @Volatile private var authSent = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (destroyed) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, requesting MTU 64")
                    _connectionStatus.value = ConnectionStatus.CONNECTING
                    authSent = false
                    g.requestMtu(64)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status=$status)")
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    authSent = false
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

            commandCharacteristic = service.getCharacteristic(COMMAND_UUID)

            // Enable notifications — auth will be sent in onDescriptorWrite callback
            val notifyChar = service.getCharacteristic(BATTERY_NOTIFY_UUID)
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true)
                val desc = notifyChar.getDescriptor(CCC_DESCRIPTOR_UUID)
                if (desc != null) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (authSent) return
            authSent = true
            val cmdChar = commandCharacteristic ?: return
            val authData = BinaryBatteryParser.cmdAuth(pin)
            g.writeCharacteristic(cmdChar, authData, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            Log.i(TAG, "BLE auth sent — awaiting response")
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
        // Check for auth response (0xAF magic)
        val authResult = BinaryBatteryParser.parseAuthResponse(data)
        if (authResult != null) {
            if (authResult) {
                _connectionStatus.value = ConnectionStatus.CONNECTED
                Log.i(TAG, "BLE authenticated")
            } else {
                _connectionStatus.value = ConnectionStatus.ERROR
                Log.w(TAG, "BLE authentication denied")
            }
            return
        }
        // Normal battery data
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

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
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
