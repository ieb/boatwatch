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
        private const val KEY_URL_HISTORY = "url_history"
        private const val DEFAULT_URL = BuildConfig.DEFAULT_URL
        private const val MAX_HISTORY = 5
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

    private val _urlHistory = MutableStateFlow(loadUrlHistory())
    val urlHistory: StateFlow<List<String>> = _urlHistory

    init {
        dataSource.start(serverUrl.value)
    }

    fun updateServerUrl(url: String) {
        serverUrl.value = url
        prefs.edit().putString(KEY_URL, url).apply()
        addToHistory(url)
        dataSource.stop()
        dataSource.start(url)
    }

    private fun loadUrlHistory(): List<String> {
        val json = prefs.getString(KEY_URL_HISTORY, null) ?: return emptyList()
        return json.removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }

    private fun addToHistory(url: String) {
        val history = _urlHistory.value.toMutableList()
        history.remove(url)
        history.add(0, url)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        _urlHistory.value = history
        val json = history.joinToString(",") { "\"$it\"" }
        prefs.edit().putString(KEY_URL_HISTORY, "[$json]").apply()
    }

    override fun onCleared() {
        super.onCleared()
        dataSource.destroy()
    }
}
