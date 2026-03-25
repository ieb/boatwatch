package uk.co.tfd.boatwatch.battery.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.tfd.boatwatch.battery.BuildConfig
import uk.co.tfd.boatwatch.battery.data.*

class BatteryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS = "battery_prefs"
        private const val KEY_URL = "server_url"
        private const val DEFAULT_URL = BuildConfig.DEFAULT_URL
    }

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val dataSource: BatteryDataSource = if (BuildConfig.FAKE_DATA) {
        FakeBatteryDataSource()
    } else {
        HttpBatteryDataSource()
    }

    val state: StateFlow<BatteryState> = dataSource.state
    val connectionStatus: StateFlow<ConnectionStatus> = dataSource.connectionStatus

    val serverUrl: MutableStateFlow<String> = MutableStateFlow(
        prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
    )

    init {
        dataSource.start(serverUrl.value)
    }

    fun updateServerUrl(url: String) {
        serverUrl.value = url
        prefs.edit().putString(KEY_URL, url).apply()
        dataSource.stop()
        dataSource.start(url)
    }

    override fun onCleared() {
        super.onCleared()
        dataSource.destroy()
    }
}
