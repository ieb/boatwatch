package uk.co.tfd.boatwatch.autopilot.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

data class DiscoveredServer(val name: String, val url: String)

object ServerDiscovery {

    private val isEmulator: Boolean by lazy {
        Build.FINGERPRINT.contains("generic") ||
        Build.FINGERPRINT.contains("emulator") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.HARDWARE.contains("ranchu") ||
        Build.PRODUCT.contains("sdk") ||
        Build.PRODUCT.contains("emulator")
    }

    suspend fun discover(context: Context, timeoutMs: Long = 5000): List<DiscoveredServer> =
        coroutineScope {
            val results = mutableListOf<DiscoveredServer>()
            val lock = Any()

            // Probe known URLs in parallel
            val urls = mutableListOf("http://boatsystems.local", "http://boatsystems.local:8080")
            if (isEmulator) {
                urls.addAll(listOf("http://10.0.2.2:8080", "http://10.0.2.2"))
            }
            val probeJob = async(Dispatchers.IO) {
                urls.map { url ->
                    async {
                        probeUrl(url)?.let { synchronized(lock) { results.add(it) } }
                    }
                }.awaitAll()
            }

            // NSD service discovery
            val nsdJob = async(Dispatchers.IO) {
                discoverNsd(context, timeoutMs, lock, results)
            }

            // Wait for timeout then collect results
            delay(timeoutMs)
            probeJob.cancel()
            nsdJob.cancel()

            synchronized(lock) {
                results.distinctBy { it.url }.toList()
            }
        }

    private fun probeUrl(urlStr: String): DiscoveredServer? {
        return try {
            val conn = URL("$urlStr/api/seasmart").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..399) { conn.disconnect(); return null }
            // Read first chunk to validate — seasmart is a streaming endpoint,
            // so just check the first few lines for $PCDIN
            val reader = conn.inputStream.bufferedReader()
            var valid = false
            repeat(10) {
                val line = reader.readLine() ?: return@repeat
                if (line.startsWith("\$PCDIN")) { valid = true; return@repeat }
            }
            reader.close()
            conn.disconnect()
            if (valid) {
                val host = URL(urlStr).host
                DiscoveredServer(host, urlStr)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun discoverNsd(
        context: Context,
        timeoutMs: Long,
        lock: Any,
        results: MutableList<DiscoveredServer>,
    ) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val host = si.host?.hostAddress ?: return
                        val port = si.port
                        // In emulator, remap host IPs to 10.0.2.2
                        val effectiveHost = if (isEmulator) "10.0.2.2" else host
                        val url = "http://$effectiveHost" + if (port != 80) ":$port" else ""
                        val name = si.serviceName ?: host
                        // Validate the server has our API
                        val server = probeUrl(url)
                        if (server != null) {
                            synchronized(lock) {
                                results.add(DiscoveredServer(name, url))
                            }
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        try {
            nsdManager.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            Thread.sleep(timeoutMs)
            nsdManager.stopServiceDiscovery(listener)
        } catch (_: Exception) {
            try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
    }
}
