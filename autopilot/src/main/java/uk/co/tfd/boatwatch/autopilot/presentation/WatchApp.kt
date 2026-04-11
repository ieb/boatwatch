package uk.co.tfd.boatwatch.autopilot.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import uk.co.tfd.boatwatch.autopilot.viewmodel.AutopilotViewModel

@Composable
fun WatchApp(vm: AutopilotViewModel = viewModel()) {
    val navController = rememberSwipeDismissableNavController()
    val apState by vm.state.collectAsStateWithLifecycle()
    val connState by vm.connectionState.collectAsStateWithLifecycle()
    val demoMode by vm.demoMode.collectAsStateWithLifecycle()
    val bleDeviceName by vm.bleDeviceName.collectAsStateWithLifecycle()
    val blePin by vm.blePin.collectAsStateWithLifecycle()

    AutopilotWatchTheme {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = "main",
        ) {
            composable("main") {
                MainScreen(
                    state = apState,
                    connectionState = connState,
                    onAdjust = { delta -> vm.adjustTarget(delta) },
                    onStandby = { vm.standby() },
                    onCycleMode = { vm.cycleMode() },
                    onSettings = { navController.navigate("settings") },
                )
            }

            composable("settings") {
                SettingsScreen(
                    connectionState = connState,
                    demoMode = demoMode,
                    bleDeviceName = bleDeviceName,
                    blePin = blePin,
                    onSetDemoMode = { vm.setDemoMode(it) },
                    onSelectBleDevice = { addr, name -> vm.selectBleDevice(addr, name) },
                    onSetBlePin = { vm.setBlePin(it) },
                )
            }
        }
    }
}
