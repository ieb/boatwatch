package uk.co.tfd.boatwatch.autopilot.presentation

import android.Manifest
import android.app.RemoteInput
import android.bluetooth.BluetoothAdapter
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
import uk.co.tfd.boatwatch.autopilot.network.ServerDiscovery
import uk.co.tfd.boatwatch.autopilot.network.DiscoveredServer
import uk.co.tfd.boatwatch.autopilot.protocol.ConnectionState
import uk.co.tfd.boatwatch.autopilot.protocol.TransportMode

data class ScannedBleDevice(val name: String, val address: String)

@Composable
fun SettingsScreen(
    serverUrl: String,
    connectionState: ConnectionState,
    urlHistory: List<String>,
    demoMode: Boolean,
    transportMode: TransportMode,
    bleDeviceName: String?,
    onUpdateUrl: (String) -> Unit,
    onSetDemoMode: (Boolean) -> Unit,
    onSetTransportMode: (TransportMode) -> Unit,
    onSelectBleDevice: (address: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var discovering by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }

    // BLE scan state
    var bleScanning by remember { mutableStateOf(false) }
    var bleDevices by remember { mutableStateOf<List<ScannedBleDevice>>(emptyList()) }

    // BLE permission handling
    var blePermGranted by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        blePermGranted = results.values.all { it }
    }

    // RemoteInput launcher
    val inputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val results = RemoteInput.getResultsFromIntent(data)
        val url = results?.getCharSequence("url")?.toString()
        if (!url.isNullOrBlank()) {
            onUpdateUrl(url)
        }
    }

    // Build preset list: default + history (deduplicated)
    val presets = remember(urlHistory) {
        val list = mutableListOf("http://boatsystems.local")
        urlHistory.forEach { url ->
            if (url !in list) list.add(url)
        }
        list
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 32.dp),
    ) {
        // Title
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
                toggleControl = {
                    Switch(checked = demoMode)
                },
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
        // Transport selector
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Chip(
                    onClick = { onSetTransportMode(TransportMode.HTTP) },
                    label = { Text("WiFi") },
                    colors = if (transportMode == TransportMode.HTTP)
                        ChipDefaults.primaryChipColors()
                    else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.weight(1f),
                )
                Chip(
                    onClick = { onSetTransportMode(TransportMode.BLE) },
                    label = { Text("Bluetooth") },
                    colors = if (transportMode == TransportMode.BLE)
                        ChipDefaults.primaryChipColors()
                    else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.weight(1f),
                )
            }
        }

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
            val targetText = if (transportMode == TransportMode.BLE) {
                bleDeviceName ?: "No device selected"
            } else {
                serverUrl
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = targetText,
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

        if (transportMode == TransportMode.HTTP) {
            // === WiFi settings ===

            // Find Server button
            item {
                Chip(
                    onClick = {
                        if (!discovering) {
                            discovering = true
                            discovered = emptyList()
                            scope.launch {
                                discovered = ServerDiscovery.discover(context)
                                discovering = false
                            }
                        }
                    },
                    label = {
                        Text(
                            text = if (discovering) "Searching..." else "Find Server",
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = if (!discovering && discovered.isNotEmpty()) {
                        { Text("Found ${discovered.size}") }
                    } else null,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Discovery results
            if (discovered.isNotEmpty()) {
                items(discovered.size) { index ->
                    val server = discovered[index]
                    Chip(
                        onClick = { onUpdateUrl(server.url) },
                        label = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(server.url, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = if (server.url == serverUrl) ChipDefaults.primaryChipColors()
                        else ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Preset URLs
            items(presets.size) { index ->
                val url = presets[index]
                Chip(
                    onClick = { onUpdateUrl(url) },
                    label = {
                        Text(
                            text = url.removePrefix("http://"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = if (url == serverUrl) ChipDefaults.primaryChipColors()
                    else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Enter URL manually
            item {
                Chip(
                    onClick = {
                        val remoteInput = RemoteInput.Builder("url")
                            .setLabel("Server URL")
                            .build()
                        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
                        inputLauncher.launch(intent)
                    },
                    label = { Text("Enter URL...") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            // === Bluetooth settings ===

            // Scan for devices
            item {
                Chip(
                    onClick = {
                        if (bleScanning) return@Chip
                        // Request permissions first
                        permLauncher.launch(arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ))

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
                                            val name = result.device.name ?: result.device.address
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

            // BLE scan results
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
        }
        } // end if (!demoMode)
    }
}
