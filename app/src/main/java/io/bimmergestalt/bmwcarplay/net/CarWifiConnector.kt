package io.bimmergestalt.bmwcarplay.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2Parsers
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Joins the BMW head unit's CarPlay Wi-Fi hotspot (2.4 GHz or 5 GHz) via [WifiNetworkSpecifier]
 * without disconnecting Bluetooth iAP2, binds the process to [carNetwork] so traffic and mDNS
 * never escape to 4G/5G cellular data, acquires a [WifiManager.MulticastLock] while active,
 * and also runs a passive Wi-Fi observer so manual connection via Android Wi-Fi settings is
 * detected and adopted cleanly without ever unregistering an active [WifiNetworkSpecifier] callback.
 */
class CarWifiConnector(
    context: Context,
    private val logger: DiagnosticLogger,
) {
    companion object {
        /**
         * Shorter than BMW's ~60s Wi-Fi wait window so a stalled Android/One UI specifier
         * sheet fails fast enough to allow a clean foreground retry before BMW drops CarPlay.
         */
        private const val SPECIFIER_TIMEOUT_MS = 25_000
    }

    data class CarNetworkInfo(
        val network: Network,
        val ssid: String,
        val interfaceName: String?,
        val localIpv4: String?,
        val localIpv4PrefixLength: Int = 24,
        val localIpv6Addresses: List<String>,
        val gatewayIpv4: String?,
        val gatewayIpv6: String?,
        val dnsServers: List<String>,
        val mtu: Int,
    ) {
        fun formatReportHr(): String {
            val v4Label = if (localIpv4 != null) "$localIpv4/$localIpv4PrefixLength" else "<nema>"
            return "BMW Wi-Fi mreža dostupna (SSID=\"$ssid\"):\n" +
                "  Sučelje: ${interfaceName ?: "?"} | MTU=$mtu\n" +
                "  Mobitel IPv4: $v4Label\n" +
                "  Mobitel IPv6: ${if (localIpv6Addresses.isEmpty()) "<nema>" else localIpv6Addresses.joinToString(", ")}\n" +
                "  DHCP Gateway IPv4 (auto): ${gatewayIpv4 ?: "<nema>"}\n" +
                "  Gateway IPv6 (auto): ${gatewayIpv6 ?: "<nema>"}\n" +
                "  DNS: ${if (dnsServers.isEmpty()) "<nema>" else dnsServers.joinToString(", ")}"
        }
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val specifierCallback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val passiveCallback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val activeNetwork = AtomicReference<Network?>(null)
    private val activeInfo = AtomicReference<CarNetworkInfo?>(null)
    private val lastObservedWifiNetwork = AtomicReference<Network?>(null)
    private val lastObservedWifiSsid = AtomicReference<String?>(null)
    private val connectedToCarWifi = AtomicBoolean(false)
    private val multicastLockRef = AtomicReference<WifiManager.MulticastLock?>(null)

    fun currentNetworkInfo(): CarNetworkInfo? = activeInfo.get()

    fun isSpecifierActive(): Boolean = specifierCallback.get() != null

    fun isWifiEnabled(): Boolean = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(true)

    /**
     * Starts a passive Wi-Fi observer (no system dialog) that watches for a manual connection
     * to [targetSsid] in Android Wi-Fi settings.
     *
     * CRITICAL: While [specifierCallback] is active, the passive observer NEVER unregisters
     * [specifierCallback] and NEVER races with it — unregistering a [WifiNetworkSpecifier]
     * callback immediately causes Android to tear down the Wi-Fi connection!
     */
    fun startPassiveObserver(
        targetSsid: String,
        onConnected: (CarNetworkInfo) -> Unit,
        onLinkUpdated: (CarNetworkInfo) -> Unit,
        onLost: (reasonHr: String) -> Unit,
    ) {
        stopPassiveObserver()
        val cleanTarget = targetSsid.trim()
        if (cleanTarget.isEmpty()) return

        val passiveRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                runCatching {
                    lastObservedWifiNetwork.set(network)
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    inspectPassiveWifi(network, caps, cleanTarget, onConnected, onLinkUpdated)
                }.onFailure { e ->
                    logger.log("WIFI", "Pasivni onAvailable iznimka: ${e.message}")
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                runCatching {
                    lastObservedWifiNetwork.set(network)
                    inspectPassiveWifi(network, networkCapabilities, cleanTarget, onConnected, onLinkUpdated)
                }.onFailure { e ->
                    logger.log("WIFI", "Pasivni onCapabilitiesChanged iznimka: ${e.message}")
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                runCatching {
                    if (activeNetwork.get() == network && specifierCallback.get() == null) {
                        handleLinkPropertiesUpdated(network, cleanTarget, linkProperties, onConnected, onLinkUpdated)
                    }
                }.onFailure { e ->
                    logger.log("WIFI", "Pasivni onLinkPropertiesChanged iznimka: ${e.message}")
                }
            }

            override fun onLost(network: Network) {
                runCatching {
                    lastObservedWifiNetwork.compareAndSet(network, null)
                    if (activeNetwork.get() == network && specifierCallback.get() == null) {
                        handleNetworkLost(network, cleanTarget, onLost)
                    }
                }.onFailure { e ->
                    logger.log("WIFI", "Pasivni onLost iznimka: ${e.message}")
                }
            }
        }

        passiveCallback.set(cb)
        try {
            connectivityManager.registerNetworkCallback(passiveRequest, cb)
            logger.log("WIFI", "Pasivni Wi-Fi monitor aktivan za \"$cleanTarget\" (detektira ručno spajanje kad specifier nije aktivan)")
        } catch (e: Exception) {
            passiveCallback.compareAndSet(cb, null)
            logger.log("WIFI", "Napomena: pasivni Wi-Fi monitor nije pokrenut: ${e.message}")
        }
    }

    /**
     * Initiates a foreground [WifiNetworkSpecifier] request for the car's Wi-Fi hotspot.
     * Supports Open (0), WPA/WPA2 (2), and WPA3 (3/4) based on 0x5703 securityType.
     * Keeps [specifierCallback] registered for the entire lifetime of the Wi-Fi session!
     */
    fun connect(
        config: Iap2Parsers.WifiConfiguration,
        onConnected: (CarNetworkInfo) -> Unit,
        onLinkUpdated: (CarNetworkInfo) -> Unit,
        onUnavailable: (reasonHr: String) -> Unit,
        onLost: (reasonHr: String) -> Unit,
    ) {
        releaseSpecifierOnly()
        if (connectedToCarWifi.get() && activeNetwork.get() != null) {
            releaseActiveBinding()
        }

        val ssid = config.ssid.trim()
        if (ssid.isEmpty()) {
            val msg = "SSID iz 0x5703 je prazan — nije moguće pokrenuti Wi-Fi spajanje"
            logger.log("WIFI", "GREŠKA: $msg")
            onUnavailable(msg)
            return
        }

        val wifiOn = isWifiEnabled()
        logger.log("WIFI", "Stanje Wi-Fi radija mobitela: ${if (wifiOn) "UKLJUČEN" else "ISKLJUČEN!"}")
        if (!wifiOn) {
            val msg = "Wi-Fi na mobitelu je isključen — uključi Wi-Fi pa klikni Ponovi Wi-Fi spajanje"
            logger.log("WIFI", "GREŠKA: $msg")
            onUnavailable(msg)
            return
        }

        if (passiveCallback.get() == null) {
            startPassiveObserver(ssid, onConnected, onLinkUpdated, onLost)
        }

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        when (config.securityType) {
            Iap2Parsers.WifiSecurityType.NONE -> {
                logger.log("WIFI", "Kreiram WifiNetworkSpecifier za OTVORENU mrežu SSID=\"$ssid\"")
            }

            Iap2Parsers.WifiSecurityType.WPA3,
            Iap2Parsers.WifiSecurityType.WPA3_TRANSITION,
            -> {
                if (config.passphrase.isNotEmpty()) {
                    logger.log(
                        "WIFI",
                        "Kreiram WifiNetworkSpecifier (${config.securityType.labelHr}) SSID=\"$ssid\" (kanal=${config.channel})",
                    )
                    specifierBuilder.setWpa3Passphrase(config.passphrase)
                } else {
                    logger.log("WIFI", "UPOZORENJE: WPA3 mreža bez lozinke — pokušavam bez lozinke")
                }
            }

            Iap2Parsers.WifiSecurityType.WEP -> {
                logger.log("WIFI", "UPOZORENJE: Auto prijavio WEP (${config.securityRawCode}) — Android WifiNetworkSpecifier ne podržava WEP, pokušavam WPA2 fallback")
                if (config.passphrase.isNotEmpty()) {
                    specifierBuilder.setWpa2Passphrase(config.passphrase)
                }
            }

            Iap2Parsers.WifiSecurityType.WPA_WPA2,
            Iap2Parsers.WifiSecurityType.UNKNOWN,
            -> {
                if (config.passphrase.isNotEmpty()) {
                    logger.log(
                        "WIFI",
                        "Kreiram WifiNetworkSpecifier (WPA2-PSK) SSID=\"$ssid\" (kanal=${config.channel})",
                    )
                    specifierBuilder.setWpa2Passphrase(config.passphrase)
                } else {
                    logger.log("WIFI", "Kreiram WifiNetworkSpecifier bez lozinke SSID=\"$ssid\"")
                }
            }
        }

        val specifier = try {
            specifierBuilder.build()
        } catch (e: Exception) {
            val msg = "Nevaljan WifiNetworkSpecifier za SSID=\"$ssid\": ${e.message}"
            logger.log("WIFI", "GREŠKA: $msg")
            onUnavailable(msg)
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching {
                    promoteToActiveCarNetwork(
                        network = network,
                        ssid = ssid,
                        sourceLabel = "WifiNetworkSpecifier",
                        onConnected = onConnected,
                        onLinkUpdated = onLinkUpdated,
                    )
                }.onFailure { e ->
                    logger.log("WIFI", "GREŠKA u specifier onAvailable: ${e.message}")
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                runCatching {
                    if (activeNetwork.get() != network) return@runCatching
                    handleLinkPropertiesUpdated(network, ssid, linkProperties, onConnected, onLinkUpdated)
                }.onFailure { e ->
                    logger.log("WIFI", "GREŠKA u specifier onLinkPropertiesChanged: ${e.message}")
                }
            }

            override fun onUnavailable() {
                specifierCallback.compareAndSet(this, null)
                if (connectedToCarWifi.get()) {
                    logger.log("WIFI", "WifiNetworkSpecifier onUnavailable zanemaren jer je BMW Wi-Fi već aktivan")
                    return
                }
                logger.log(
                    "WIFI",
                    "✗ onUnavailable (${SPECIFIER_TIMEOUT_MS / 1000}s): Spajanje na BMW Wi-Fi \"$ssid\" nije uspjelo ili je sistemski dijalog blokiran/otkazan",
                )
                onUnavailable("Spajanje na \"$ssid\" nije uspjelo (${SPECIFIER_TIMEOUT_MS / 1000}s) — klikni Ponovi ili se spoji ručno")
            }

            override fun onLost(network: Network) {
                runCatching {
                    handleNetworkLost(network, ssid, onLost)
                }.onFailure { e ->
                    logger.log("WIFI", "GREŠKA u specifier onLost: ${e.message}")
                }
            }
        }

        specifierCallback.set(callback)
        logger.log(
            "WIFI",
            "Pozivam ConnectivityManager.requestNetwork za \"$ssid\" (timeout=${SPECIFIER_TIMEOUT_MS / 1000}s — kad se pojavi Samsung prozor, klikni Poveži / Connect)...",
        )
        try {
            connectivityManager.requestNetwork(request, callback, SPECIFIER_TIMEOUT_MS)
        } catch (e: SecurityException) {
            specifierCallback.compareAndSet(callback, null)
            val msg = "Nedostaju Wi-Fi/mrežne dozvole za requestNetwork: ${e.message}"
            logger.log("WIFI", "GREŠKA: $msg")
            onUnavailable(msg)
        } catch (e: Exception) {
            specifierCallback.compareAndSet(callback, null)
            val msg = "Greška pri requestNetwork: ${e.message}"
            logger.log("WIFI", "GREŠKA: $msg")
            onUnavailable(msg)
        }
    }

    /**
     * Adopts whichever Wi-Fi network the phone is currently connected to (e.g., if the user
     * manually connected to `BMW 04990 CarPlay` in Android Wi-Fi settings and Android redacted
     * the SSID to `<unknown ssid>`). Never unregisters an active [specifierCallback].
     */
    fun adoptObservedWifi(
        targetSsid: String,
        onConnected: (CarNetworkInfo) -> Unit,
        onLinkUpdated: (CarNetworkInfo) -> Unit,
        onUnavailable: (reasonHr: String) -> Unit,
    ) {
        val candidate = lastObservedWifiNetwork.get()
            ?: connectivityManager.activeNetwork?.takeIf { net ->
                connectivityManager.getNetworkCapabilities(net)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }

        if (candidate == null) {
            val msg = "Mobitel trenutno nije spojen ni na jednu Wi-Fi mrežu — spoji se na \"$targetSsid\" u Wi-Fi postavkama"
            logger.log("WIFI", "UPOZORENJE: $msg")
            onUnavailable(msg)
            return
        }

        val observedSsid = lastObservedWifiSsid.get()?.takeIf { it.isNotBlank() } ?: targetSsid
        logger.log(
            "WIFI",
            "Korisnik potvrdio ručnu Wi-Fi vezu — preuzimam aktivnu Wi-Fi mrežu $candidate (SSID=\"$observedSsid\")",
        )
        promoteToActiveCarNetwork(
            network = candidate,
            ssid = targetSsid.ifBlank { observedSsid },
            sourceLabel = "Ručna Android Wi-Fi veza",
            onConnected = onConnected,
            onLinkUpdated = onLinkUpdated,
        )
    }

    /**
     * Releases all active Wi-Fi requests, passive observers, multicast lock, and process binding.
     */
    fun release() {
        releaseSpecifierOnly()
        stopPassiveObserver()
        releaseActiveBinding()
        lastObservedWifiNetwork.set(null)
        lastObservedWifiSsid.set(null)
    }

    private fun inspectPassiveWifi(
        network: Network,
        caps: NetworkCapabilities?,
        targetSsid: String,
        onConnected: (CarNetworkInfo) -> Unit,
        onLinkUpdated: (CarNetworkInfo) -> Unit,
    ) {
        val rawSsid = extractSsid(caps)
        val prevSsid = lastObservedWifiSsid.getAndSet(rawSsid)
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (rawSsid != prevSsid) {
            logger.log(
                "WIFI",
                "Pasivni Wi-Fi monitor vidi mrežu $network: SSID=${rawSsid ?: "<skriveno>"} | internetValidated=$hasInternet",
            )
        }
        // CRITICAL: If WifiNetworkSpecifier is active, DO NOT interfere or unregister it!
        // Unregistering specifierCallback immediately tears down the specifier-owned Wi-Fi connection!
        if (specifierCallback.get() != null) {
            return
        }
        if (!rawSsid.isNullOrBlank() &&
            rawSsid.equals(targetSsid, ignoreCase = true) &&
            !connectedToCarWifi.get()
        ) {
            logger.log("WIFI", "✓ Pasivni monitor pronašao ručnu Wi-Fi vezu na \"$targetSsid\" — automatski preuzimam mrežu!")
            promoteToActiveCarNetwork(
                network = network,
                ssid = targetSsid,
                sourceLabel = "Pasivni Wi-Fi monitor (ručno spajanje)",
                onConnected = onConnected,
                onLinkUpdated = onLinkUpdated,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun extractSsid(caps: NetworkCapabilities?): String? {
        val fromCaps = (caps?.transportInfo as? WifiInfo)?.ssid
        val cleaned = normalizeSsid(fromCaps)
        if (cleaned != null) return cleaned
        @Suppress("DEPRECATION")
        val fromMgr = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        return normalizeSsid(fromMgr)
    }

    private fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().removeSurrounding("\"")
        if (trimmed.isEmpty() || trimmed.equals("<unknown ssid>", ignoreCase = true)) return null
        return trimmed
    }

    private fun promoteToActiveCarNetwork(
        network: Network,
        ssid: String,
        sourceLabel: String,
        onConnected: (CarNetworkInfo) -> Unit,
        onLinkUpdated: (CarNetworkInfo) -> Unit,
    ) {
        activeNetwork.set(network)
        // Intentionally DO NOT call connectivityManager.bindProcessToNetwork(network):
        // keeping the process unbound lets 5G cellular internet keep working normally on Galaxy S25
        // while every CarPlay TCP/UDP socket and NSD service explicitly binds to `carNetwork`.
        acquireMulticastLock()
        val lp = connectivityManager.getLinkProperties(network)
        val info = buildNetworkInfo(network, ssid, lp)
        activeInfo.set(info)

        if (connectedToCarWifi.compareAndSet(false, true)) {
            logger.log(
                "WIFI",
                "✓ BMW Wi-Fi spojen ($sourceLabel, net=$network) — samo CarPlay socketi vezani na carNetwork (5G internet + BT iAP2 ostaju aktivni!)",
            )
            logger.log("WIFI", info.formatReportHr())
            onConnected(info)
        } else {
            onLinkUpdated(info)
        }
    }

    private fun handleLinkPropertiesUpdated(
        network: Network,
        ssid: String,
        linkProperties: LinkProperties,
        onConnected: (CarNetworkInfo) -> Unit,
        onLinkUpdated: (CarNetworkInfo) -> Unit,
    ) {
        val updated = buildNetworkInfo(network, ssid, linkProperties)
        val prev = activeInfo.getAndSet(updated)
        val changed = prev == null ||
            prev.localIpv4 != updated.localIpv4 ||
            prev.localIpv4PrefixLength != updated.localIpv4PrefixLength ||
            prev.gatewayIpv4 != updated.gatewayIpv4 ||
            prev.gatewayIpv6 != updated.gatewayIpv6 ||
            prev.localIpv6Addresses != updated.localIpv6Addresses

        if (changed) {
            logger.log("WIFI", "Ažurirani LinkProperties za BMW Wi-Fi:\n${updated.formatReportHr()}")
        }
        if (connectedToCarWifi.compareAndSet(false, true)) {
            onConnected(updated)
        } else if (changed) {
            onLinkUpdated(updated)
        }
    }

    private fun handleNetworkLost(
        network: Network,
        ssid: String,
        onLost: (reasonHr: String) -> Unit,
    ) {
        if (activeNetwork.compareAndSet(network, null)) {
            connectedToCarWifi.set(false)
            activeInfo.set(null)
            releaseMulticastLock()
            logger.log("WIFI", "BMW Wi-Fi mreža \"$ssid\" je izgubljena ($network)")
            onLost("Izgubljena veza s BMW Wi-Fi mrežom \"$ssid\"")
        }
    }

    private fun releaseSpecifierOnly() {
        specifierCallback.getAndSet(null)?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
    }

    private fun stopPassiveObserver() {
        passiveCallback.getAndSet(null)?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
    }

    private fun releaseActiveBinding() {
        val hadNet = activeNetwork.getAndSet(null) != null
        connectedToCarWifi.set(false)
        activeInfo.set(null)
        releaseMulticastLock()
        if (hadNet) {
            logger.log("WIFI", "Oslobođena BMW Wi-Fi mreža")
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLockRef.get()?.isHeld == true) return
        runCatching {
            val lock = wifiManager?.createMulticastLock("BMWCarPlayProbe::mDNS")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            if (lock != null) {
                multicastLockRef.set(lock)
                logger.log("WIFI", "WifiManager.MulticastLock aktiviran za mDNS (_airplay / _carplay-ctrl)")
            }
        }.onFailure { e ->
            logger.log("WIFI", "Napomena: MulticastLock nije aktiviran: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        multicastLockRef.getAndSet(null)?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    private fun buildNetworkInfo(
        network: Network,
        ssid: String,
        lp: LinkProperties?,
    ): CarNetworkInfo {
        val iface = lp?.interfaceName
        var localV4: String? = null
        var localV4Prefix = 24
        val localV6 = ArrayList<String>()

        lp?.linkAddresses?.forEach { linkAddr ->
            val addr: InetAddress = linkAddr.address
            when (addr) {
                is Inet4Address -> if (localV4 == null) {
                    localV4 = addr.hostAddress
                    localV4Prefix = linkAddr.prefixLength.takeIf { it in 1..32 } ?: 24
                }

                is Inet6Address -> {
                    val host = addr.hostAddress
                    if (!host.isNullOrBlank()) {
                        val withScope = if (addr.isLinkLocalAddress && !host.contains('%') && !iface.isNullOrBlank()) {
                            "$host%$iface"
                        } else {
                            host
                        }
                        localV6.add(withScope)
                    }
                }
            }
        }

        var gwV4: String? = null
        var gwV6: String? = null
        lp?.routes?.forEach { route ->
            val gw = route.gateway ?: return@forEach
            if (gw.isAnyLocalAddress) return@forEach
            when (gw) {
                is Inet4Address -> {
                    if (route.isDefaultRoute || gwV4 == null) gwV4 = gw.hostAddress
                }

                is Inet6Address -> {
                    val host = gw.hostAddress
                    if (!host.isNullOrBlank() && (route.isDefaultRoute || gwV6 == null)) {
                        gwV6 = if (gw.isLinkLocalAddress && !host.contains('%') && !iface.isNullOrBlank()) {
                            "$host%$iface"
                        } else {
                            host
                        }
                    }
                }
            }
        }

        if (gwV4 == null) {
            val dhcpAddr = lp?.dhcpServerAddress
            if (dhcpAddr is Inet4Address && !dhcpAddr.isAnyLocalAddress) {
                gwV4 = dhcpAddr.hostAddress
            }
        }

        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        val mtu = lp?.mtu ?: 0

        return CarNetworkInfo(
            network = network,
            ssid = ssid,
            interfaceName = iface,
            localIpv4 = localV4,
            localIpv4PrefixLength = localV4Prefix,
            localIpv6Addresses = localV6,
            gatewayIpv4 = gwV4,
            gatewayIpv6 = gwV6,
            dnsServers = dns,
            mtu = mtu,
        )
    }
}
