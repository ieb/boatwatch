package uk.co.tfd.boatwatch.battery.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import uk.co.tfd.boatwatch.battery.data.ConnectionStatus

@Composable
fun SettingsScreen(
    serverUrl: String,
    connectionStatus: ConnectionStatus,
    onUpdateUrl: (String) -> Unit,
) {
    var editUrl by remember(serverUrl) { mutableStateOf(serverUrl) }

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

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            Text(
                text = "Server URL",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }

        item {
            Text(
                text = editUrl,
                fontSize = 14.sp,
                color = MaterialTheme.colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            val statusText = when (connectionStatus) {
                ConnectionStatus.CONNECTED -> "Connected"
                ConnectionStatus.CONNECTING -> "Connecting..."
                ConnectionStatus.ERROR -> "Connection Error"
                ConnectionStatus.DISCONNECTED -> "Disconnected"
            }
            val statusColor = when (connectionStatus) {
                ConnectionStatus.CONNECTED -> MaterialTheme.colors.primary
                ConnectionStatus.CONNECTING -> MaterialTheme.colors.secondary
                ConnectionStatus.ERROR -> MaterialTheme.colors.error
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colors.onSurfaceVariant
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
