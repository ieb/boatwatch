package uk.co.tfd.boatwatch.autopilot.presentation

import android.Manifest
import android.app.RemoteInput
import android.bluetooth.le.*
import android.os.ParcelUuid
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.tfd.boatwatch.autopilot.network.BleAutopilotClient
import uk.co.tfd.boatwatch.autopilot.protocol.ConnectionState

data class ScannedBleDevice(val name: String, val address: String)

@Composable
fun SettingsScreen(
    connectionState: ConnectionState,
    demoMode: Boolean,
    bleDeviceName: String?,
    blePin: String,
    onSetDemoMode: (Boolean) -> Unit,
    onSelectBleDevice: (address: String, name: String) -> Unit,
    onSetBlePin: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bleScanning by remember { mutableStateOf(false) }
    var bleDevices by remember { mutableStateOf<List<ScannedBleDevice>>(emptyList()) }

    fun startBleScan() {
        if (bleScanning) return
        bleScanning = true
        bleDevices = emptyList()
        scope.launch {
            val results = mutableListOf<ScannedBleDevice>()
            val adapter = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            val scanner = adapter?.bluetoothLeScanner
            if (scanner != null) {
                val found = mutableSetOf<String>()
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val addr = result.device.address
                        if (addr !in found) {
                            found.add(addr)
                            val name = result.scanRecord?.deviceName
                                ?: result.device.name
                                ?: "BoatWatch"
                            results.add(ScannedBleDevice(name, addr))
                            bleDevices = results.toList()
                        }
                    }
                }
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleAutopilotClient.SERVICE_UUID))
                    .build()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                try {
                    scanner.startScan(listOf(filter), settings, callback)
                    delay(10000)
                    scanner.stopScan(callback)
                } catch (_: SecurityException) { }
            }
            bleScanning = false
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBleScan()
        }
    }

    val pinLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val results = RemoteInput.getResultsFromIntent(data)
        val pin = results?.getCharSequence("pin")?.toString()?.take(4)
        if (!pin.isNullOrBlank()) {
            onSetBlePin(pin)
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 32.dp),
    ) {
        item {
            Text(
                text = "Settings",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
            )
        }

        // Demo mode toggle
        item {
            ToggleChip(
                checked = demoMode,
                onCheckedChange = { onSetDemoMode(it) },
                label = { Text("Demo Mode") },
                toggleControl = { Switch(checked = demoMode) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (demoMode) {
            item {
                Text(
                    text = "Using simulated data",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (!demoMode) {
            // Connection status
            item {
                val statusColor = when (connectionState) {
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                    ConnectionState.CONNECTING -> MaterialTheme.colors.secondary
                    ConnectionState.ERROR -> MaterialTheme.colors.error
                    ConnectionState.DISCONNECTED -> MaterialTheme.colors.onSurfaceVariant
                }
                val statusText = when (connectionState) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.ERROR -> "Error"
                    ConnectionState.DISCONNECTED -> "Disconnected"
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = bleDeviceName ?: "No device selected",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Scan for devices
            item {
                Chip(
                    onClick = {
                        if (bleScanning) return@Chip
                        val hasPerms = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (hasPerms) {
                            startBleScan()
                        } else {
                            permLauncher.launch(arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                            ))
                        }
                    },
                    label = {
                        Text(
                            text = if (bleScanning) "Scanning..." else "Scan for Devices",
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = if (!bleScanning && bleDevices.isNotEmpty()) {
                        { Text("Found ${bleDevices.size}") }
                    } else null,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Scan results
            if (bleDevices.isNotEmpty()) {
                items(bleDevices.size) { index ->
                    val device = bleDevices[index]
                    Chip(
                        onClick = { onSelectBleDevice(device.address, device.name) },
                        label = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(device.address, fontSize = 10.sp) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // PIN entry
            item {
                Chip(
                    onClick = {
                        val remoteInput = RemoteInput.Builder("pin")
                            .setLabel("BLE PIN (4 digits)")
                            .build()
                        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
                        pinLauncher.launch(intent)
                    },
                    label = { Text("PIN: $blePin") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
