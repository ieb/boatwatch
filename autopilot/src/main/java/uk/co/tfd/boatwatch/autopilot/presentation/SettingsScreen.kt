package uk.co.tfd.boatwatch.autopilot.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import uk.co.tfd.boatwatch.autopilot.protocol.ConnectionState

@Composable
fun SettingsScreen(
    serverUrl: String,
    connectionState: ConnectionState,
    onUpdateUrl: (String) -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 32.dp),
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

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Text(
                text = "Server URL",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }

        item {
            Text(
                text = serverUrl,
                fontSize = 14.sp,
                color = MaterialTheme.colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }

        item {
            val statusText = when (connectionState) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting..."
                ConnectionState.ERROR -> "Connection Error"
                ConnectionState.DISCONNECTED -> "Disconnected"
            }
            val statusColor = when (connectionState) {
                ConnectionState.CONNECTED -> MaterialTheme.colors.primary
                ConnectionState.CONNECTING -> MaterialTheme.colors.secondary
                ConnectionState.ERROR -> MaterialTheme.colors.error
                ConnectionState.DISCONNECTED -> MaterialTheme.colors.onSurfaceVariant
            }
            Text(
                text = statusText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}
