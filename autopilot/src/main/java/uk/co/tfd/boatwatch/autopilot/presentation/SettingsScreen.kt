package uk.co.tfd.boatwatch.autopilot.presentation

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.launch
import uk.co.tfd.boatwatch.autopilot.BuildConfig
import uk.co.tfd.boatwatch.autopilot.network.ServerDiscovery
import uk.co.tfd.boatwatch.autopilot.network.DiscoveredServer
import uk.co.tfd.boatwatch.autopilot.protocol.ConnectionState

@Composable
fun SettingsScreen(
    serverUrl: String,
    connectionState: ConnectionState,
    urlHistory: List<String>,
    onUpdateUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var discovering by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }

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

    // Build preset list: default + debug emulator + history (deduplicated)
    val presets = remember(urlHistory) {
        val list = mutableListOf("http://boatsystems.local")
        if (BuildConfig.DEBUG) {
            list.add("http://10.0.2.2:8080")
        }
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

        // Current URL and status
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
                    text = serverUrl,
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
    }
}
