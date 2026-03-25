package uk.co.tfd.boatwatch.battery.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import uk.co.tfd.boatwatch.battery.viewmodel.BatteryViewModel

@Composable
fun WatchApp(vm: BatteryViewModel = viewModel()) {
    val navController = rememberSwipeDismissableNavController()
    val batteryState by vm.state.collectAsStateWithLifecycle()
    val connectionStatus by vm.connectionStatus.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()

    BatteryWatchTheme {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = "battery",
        ) {
            composable("battery") {
                BatteryScreen(
                    state = batteryState,
                    connectionStatus = connectionStatus,
                    onSettings = { navController.navigate("settings") },
                )
            }

            composable("settings") {
                SettingsScreen(
                    serverUrl = serverUrl,
                    connectionStatus = connectionStatus,
                    onUpdateUrl = { vm.updateServerUrl(it) },
                )
            }
        }
    }
}
