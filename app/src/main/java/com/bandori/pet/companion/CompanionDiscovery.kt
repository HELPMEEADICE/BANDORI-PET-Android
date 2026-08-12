package com.bandori.pet.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredDesktop(
    val instanceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocolVersion: Int,
)

class CompanionDiscovery(context: Context) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    private val mutableItems = MutableStateFlow<List<DiscoveredDesktop>>(emptyList())
    private var running = false
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    val items: StateFlow<List<DiscoveredDesktop>> = mutableItems

    private fun markStopped() {
        runCatching { multicastLock?.release() }
        multicastLock = null
        running = false
    }

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = markStopped()
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = markStopped()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = markStopped()
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            mutableItems.value = mutableItems.value.filterNot { it.name == serviceInfo.serviceName }
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.startsWith("_bandoripet._tcp")) return
            @Suppress("DEPRECATION")
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val attributes = resolved.attributes
                    fun property(name: String): String = attributes[name]
                        ?.let { String(it, StandardCharsets.UTF_8) }
                        .orEmpty()
                    val item = DiscoveredDesktop(
                        instanceId = property("instance"),
                        name = property("name").ifBlank { resolved.serviceName },
                        host = resolved.host?.hostAddress.orEmpty().substringBefore('%'),
                        port = resolved.port,
                        protocolVersion = property("v").toIntOrNull() ?: 0,
                    )
                    if (item.host.isBlank() || item.protocolVersion != 1) return
                    mutableItems.value = (mutableItems.value.filterNot {
                        it.instanceId.isNotBlank() && it.instanceId == item.instanceId
                    } + item).sortedBy(DiscoveredDesktop::name)
                }
            })
        }
    }

    fun start() {
        if (running) return
        running = true
        multicastLock = wifiManager?.createMulticastLock("bandoripet-companion-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        if (!running) return
        runCatching { manager.stopServiceDiscovery(listener) }
        markStopped()
    }

    companion object {
        private const val SERVICE_TYPE = "_bandoripet._tcp."
    }
}
