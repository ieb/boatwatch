package uk.co.tfd.boatwatch.autopilot.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

data class DiscoveredServer(val name: String, val url: String)

object ServerDiscovery {

    suspend fun discover(context: Context, timeoutMs: Long = 5000): List<DiscoveredServer> =
        coroutineScope {
            val results = mutableListOf<DiscoveredServer>()
            val lock = Any()

            // Probe boatsystems.local directly
            val probeJob = async(Dispatchers.IO) {
                probeUrl("http://boatsystems.local")?.let {
                    synchronized(lock) { results.add(it) }
                }
                probeUrl("http://boatsystems.local:8080")?.let {
                    synchronized(lock) { results.add(it) }
                }
                // Emulator host
                probeUrl("http://10.0.2.2:8080")?.let {
                    synchronized(lock) { results.add(it) }
                }
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
            val conn = URL("$urlStr/api/store").openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..399) {
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
                        val url = "http://$host" + if (port != 80) ":$port" else ""
                        val name = si.serviceName ?: host
                        synchronized(lock) {
                            results.add(DiscoveredServer(name, url))
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
