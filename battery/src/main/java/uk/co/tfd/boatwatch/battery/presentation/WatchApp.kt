package uk.co.tfd.boatwatch.battery.presentation

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.co.tfd.boatwatch.battery.viewmodel.BatteryViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchApp(vm: BatteryViewModel = viewModel()) {
    val batteryState by vm.state.collectAsStateWithLifecycle()
    val connectionStatus by vm.connectionStatus.collectAsStateWithLifecycle()
    val demoMode by vm.demoMode.collectAsStateWithLifecycle()
    val bleDeviceName by vm.bleDeviceName.collectAsStateWithLifecycle()
    val blePin by vm.blePin.collectAsStateWithLifecycle()

    val activity = LocalContext.current as? Activity
    val pagerState = rememberPagerState(pageCount = { 2 })
    var dismissDrag by remember { mutableFloatStateOf(0f) }
    var gestureStartPage by remember { mutableIntStateOf(-1) }

    val dismissScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Record which page this gesture started on
                if (gestureStartPage == -1) {
                    gestureStartPage = pagerState.currentPage
                }
                // Only dismiss if the gesture originated on page 0
                if (gestureStartPage == 0 && pagerState.currentPage == 0 && available.x > 0) {
                    dismissDrag += available.x
                    if (dismissDrag > 150f) {
                        activity?.finish()
                    }
                    return Offset(available.x, 0f)
                }
                if (available.x < 0) {
                    dismissDrag = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                gestureStartPage = -1
                dismissDrag = 0f
                return Velocity.Zero
            }
        }
    }

    BatteryWatchTheme {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(dismissScrollConnection),
        ) { page ->
            when (page) {
                0 -> BatteryScreen(
                    state = batteryState,
                    connectionStatus = connectionStatus,
                )
                1 -> SettingsScreen(
                    connectionStatus = connectionStatus,
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
