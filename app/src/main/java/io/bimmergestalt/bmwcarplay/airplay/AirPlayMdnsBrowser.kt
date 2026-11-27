package io.bimmergestalt.bmwcarplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.net.CarWifiConnector
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers the BMW head unit's `_airplay._tcp` Bonjour/mDNS service strictly on `carNetwork`.
 *
 * - Uses API 33+ `discoverServices(..., carNetwork, executor, listener)` so queries stay on car Wi-Fi.
 * - Uses API 34+ `registerServiceInfoCallback` (with `resolveService` fallback) to capture ALL
 *   IPv4 and IPv6 link-local (`fe80::...%wlan0`) addresses.
 * - Filters discovered addresses to belong to `carNetwork`'s subnet.
 * - Logs every TXT record (`deviceid`, `features`, `srcvers`, `model`, `flags`, `pk`, `pi`, ...)
 *   and decodes `features` bit 38 (`HK_PAIRING_AND_ENCRYPT`) for Phase 2 planning.
 */
class AirPlayMdnsBrowser(
    context: Context,
    private val logger: DiagnosticLogger,
    private val onEndpointDiscovered: (DiscoveredAirPlayService) -> Unit,
) {
    data class DiscoveredAirPlayService(
        val serviceName: String,
        val port: Int,
        val addresses: List<String>,
        val txtRecords: Map<String, String>,
        val featuresAnalysis: AirPlayFeatures.FeaturesAnalysis?,
    )

    companion object {
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nsd-airplay-browser").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val activeInfoCallbacks = CopyOnWriteArrayList<NsdManager.ServiceInfoCallback>()
    private val discoveredServices = CopyOnWriteArrayList<DiscoveredAirPlayService>()

    fun currentServices(): List<DiscoveredAirPlayService> = discoveredServices.toList()

    @Synchronized
    fun start(carNetInfo: CarWifiConnector.CarNetworkInfo) {
        stop()
        running.set(true)
        discoveredServices.clear()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                logger.log("MDNS-AIRPLAY", "Pretraga $serviceType aktivna na mreži ${carNetInfo.interfaceName ?: carNetInfo.ssid}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                logger.log("MDNS-AIRPLAY", "Pretraga $serviceType zaustavljena")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                running.set(false)
                logger.log("MDNS-AIRPLAY", "✗ Pokretanje mDNS pretrage $serviceType neuspješno (code=$errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                logger.log("MDNS-AIRPLAY", "Zaustavljanje mDNS pretrage $serviceType greška (code=$errorCode)")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                logger.log(
                    "MDNS-AIRPLAY",
                    "Pronađen servis: \"${serviceInfo.serviceName}\" (${serviceInfo.serviceType}) — razrješavam adrese i TXT...",
                )
                resolveServiceCompat(serviceInfo, carNetInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                logger.log("MDNS-AIRPLAY", "Servis nestao s mreže: ${serviceInfo.serviceName}")
            }
        }

        discoveryListener = listener
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                nsdManager.discoverServices(
                    AIRPLAY_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    carNetInfo.network,
                    executor,
                    listener,
                )
            } else {
                nsdManager.discoverServices(AIRPLAY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            running.set(false)
            logger.log("MDNS-AIRPLAY", "GREŠKA pri pokretanju discoverServices: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (cb in activeInfoCallbacks) {
                runCatching { nsdManager.unregisterServiceInfoCallback(cb) }
            }
            activeInfoCallbacks.clear()
        }
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
    }

    private fun resolveServiceCompat(
        serviceInfo: NsdServiceInfo,
        carNetInfo: CarWifiConnector.CarNetworkInfo,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    logger.log(
                        "MDNS-AIRPLAY",
                        "registerServiceInfoCallback greška ($errorCode) za ${serviceInfo.serviceName} — fallback na resolveService",
                    )
                    resolveLegacy(serviceInfo, carNetInfo, attemptsLeft = 3)
                }

                override fun onServiceUpdated(resolvedInfo: NsdServiceInfo) {
                    handleResolvedInfo(resolvedInfo, carNetInfo)
                }

                override fun onServiceLost() {
                    logger.log("MDNS-AIRPLAY", "ServiceInfoCallback: servis ${serviceInfo.serviceName} izgubljen")
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            activeInfoCallbacks.add(cb)
            try {
                nsdManager.registerServiceInfoCallback(serviceInfo, executor, cb)
            } catch (e: Exception) {
                activeInfoCallbacks.remove(cb)
                resolveLegacy(serviceInfo, carNetInfo, attemptsLeft = 3)
            }
        } else {
            resolveLegacy(serviceInfo, carNetInfo, attemptsLeft = 3)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(
        serviceInfo: NsdServiceInfo,
        carNetInfo: CarWifiConnector.CarNetworkInfo,
        attemptsLeft: Int,
    ) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attemptsLeft > 1 && running.get()) {
                    executor.execute {
                        try {
                            Thread.sleep(250L)
                        } catch (_: InterruptedException) {
                            return@execute
                        }
                        if (running.get()) resolveLegacy(info, carNetInfo, attemptsLeft - 1)
                    }
                } else {
                    logger.log("MDNS-AIRPLAY", "Razrješavanje ${info.serviceName} neuspješno (code=$errorCode)")
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                handleResolvedInfo(info, carNetInfo)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                nsdManager.resolveService(serviceInfo, executor, listener)
            } else {
                nsdManager.resolveService(serviceInfo, listener)
            }
        } catch (e: Exception) {
            logger.log("MDNS-AIRPLAY", "resolveService iznimka: ${e.message}")
        }
    }

    private fun handleResolvedInfo(
        info: NsdServiceInfo,
        carNetInfo: CarWifiConnector.CarNetworkInfo,
    ) {
        val rawInetAddresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }

        val acceptedAddresses = ArrayList<String>()
        val rejectedAddresses = ArrayList<String>()
        val iface = carNetInfo.interfaceName

        for (addr in rawInetAddresses) {
            val formatted = formatAddressWithScope(addr, iface)
            if (belongsToCarSubnet(addr, carNetInfo)) {
                if (formatted !in acceptedAddresses) acceptedAddresses.add(formatted)
            } else {
                rejectedAddresses.add(formatted)
            }
        }

        val txtMap = LinkedHashMap<String, String>()
        info.attributes.entries.sortedBy { it.key.lowercase() }.forEach { (key, rawVal) ->
            val valueStr = rawVal?.let { bytes ->
                var end = bytes.size
                while (end > 0 && bytes[end - 1] == 0.toByte()) end--
                bytes.copyOf(end).toString(Charsets.UTF_8)
            }.orEmpty()
            txtMap[key] = valueStr
        }

        val featuresAnalysis = AirPlayFeatures.analyze(txtMap["features"])

        val sb = StringBuilder()
        sb.append("✓ Razriješen _airplay._tcp servis \"${info.serviceName}\" port=${info.port}")
        sb.append("\n  Prihvaćene adrese (carNetwork): $acceptedAddresses")
        if (rejectedAddresses.isNotEmpty()) {
            sb.append("\n  Odbačene adrese (druga podmreža): $rejectedAddresses")
        }
        if (txtMap.isNotEmpty()) {
            sb.append("\n  TXT zapisi (${txtMap.size}):")
            for ((k, v) in txtMap) {
                sb.append("\n    $k = $v")
            }
        } else {
            sb.append("\n  TXT zapisi: <nema>")
        }
        if (featuresAnalysis != null) {
            sb.append("\n  ").append(featuresAnalysis.formatReportHr().replace("\n", "\n  "))
        }
        logger.log("MDNS-AIRPLAY", sb.toString())

        if (acceptedAddresses.isEmpty()) {
            logger.log("MDNS-AIRPLAY", "Ignoriram servis \"${info.serviceName}\" jer nijedna adresa ne pripada carNetwork podmreži")
            return
        }

        val discovered = DiscoveredAirPlayService(
            serviceName = info.serviceName,
            port = info.port,
            addresses = acceptedAddresses,
            txtRecords = txtMap,
            featuresAnalysis = featuresAnalysis,
        )
        discoveredServices.removeAll { it.serviceName == discovered.serviceName && it.port == discovered.port }
        discoveredServices.add(discovered)
        onEndpointDiscovered(discovered)
    }

    private fun formatAddressWithScope(addr: InetAddress, ifaceName: String?): String {
        val raw = addr.hostAddress.orEmpty()
        return if (addr is Inet6Address && addr.isLinkLocalAddress && !raw.contains('%') && !ifaceName.isNullOrBlank()) {
            "$raw%$ifaceName"
        } else {
            raw
        }
    }

    private fun belongsToCarSubnet(
        addr: InetAddress,
        carNetInfo: CarWifiConnector.CarNetworkInfo,
    ): Boolean = when (addr) {
        is Inet4Address -> {
            val localV4 = carNetInfo.localIpv4
            val candidateV4 = addr.hostAddress.orEmpty()
            if (localV4.isNullOrBlank()) {
                true
            } else {
                sameIpv4Slash24(localV4, candidateV4)
            }
        }

        is Inet6Address -> {
            if (addr.isLinkLocalAddress) {
                true
            } else {
                val candPrefix = addr.hostAddress.orEmpty().substringBefore('%').lowercase().split(':').take(4)
                carNetInfo.localIpv6Addresses.any { local ->
                    local.substringBefore('%').lowercase().split(':').take(4) == candPrefix
                }
            }
        }

        else -> false
    }

    private fun sameIpv4Slash24(a: String, b: String): Boolean {
        val pa = a.split('.')
        val pb = b.split('.')
        return pa.size == 4 && pb.size == 4 && pa[0] == pb[0] && pa[1] == pb[1] && pa[2] == pb[2]
    }
}
