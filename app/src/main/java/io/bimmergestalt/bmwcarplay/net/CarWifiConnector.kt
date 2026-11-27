package io.bimmergestalt.bmwcarplay.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2Parsers
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Joins the BMW head unit's 5 GHz CarPlay Wi-Fi hotspot via [WifiNetworkSpecifier]
 * without disconnecting Bluetooth iAP2, binds the process to [carNetwork] so traffic
 * and mDNS never escape to 4G/5G cellular data, and extracts full [LinkProperties].
 */
class CarWifiConnector(
    context: Context,
    private val logger: DiagnosticLogger,
) {
    data class CarNetworkInfo(
        val network: Network,
        val ssid: String,
        val interfaceName: String?,
        val localIpv4: String?,
        val localIpv6Addresses: List<String>,
        val gatewayIpv4: String?,
        val gatewayIpv6: String?,
        val dnsServers: List<String>,
        val mtu: Int,
    ) {
        fun formatReportHr(): String =
            "BMW Wi-Fi mreža dostupna (SSID=\"$ssid\"):\n" +
                "  Sučelje: ${interfaceName ?: "?"} | MTU=$mtu\n" +
                "  Mobitel IPv4: ${localIpv4 ?: "<nema>"}\n" +
                "  Mobitel IPv6: ${if (localIpv6Addresses.isEmpty()) "<nema>" else localIpv6Addresses.joinToString(", ")}\n" +
                "  DHCP Gateway IPv4 (auto): ${gatewayIpv4 ?: "<nema>"}\n" +
                "  Gateway IPv6 (auto): ${gatewayIpv6 ?: "<nema>"}\n" +
                "  DNS: ${if (dnsServers.isEmpty()) "<nema>" else dnsServers.joinToString(", ")}"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val activeCallback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val activeNetwork = AtomicReference<Network?>(null)
    private val activeInfo = AtomicReference<CarNetworkInfo?>(null)

    fun currentNetworkInfo(): CarNetworkInfo? = activeInfo.get()

    /**
     * Initiates a WifiNetworkSpecifier request for the car's Wi-Fi hotspot.
     * Supports Open (0), WPA/WPA2 (2), and WPA3 (3/4) based on 0x5703 securityType.
     */
    fun connect(
        config: Iap2Parsers.WifiConfiguration,
        onConnected: (CarNetworkInfo) -> Unit,
        onUnavailable: (reasonHr: String) -> Unit,
        onLost: (reasonHr: String) -> Unit,
    ) {
        release()

        val ssid = config.ssid.trim()
        if (ssid.isEmpty()) {
            val msg = "SSID iz 0x5703 je prazan — nije moguće pokrenuti Wi-Fi spajanje"
            logger.log("WIFI", "GREŠKA: $msg")
            onUnavailable(msg)
            return
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

        val deliveredConnected = AtomicBoolean(false)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetwork.set(network)
                val bound = connectivityManager.bindProcessToNetwork(network)
                logger.log(
                    "WIFI",
                    "✓ onAvailable($network) — bindProcessToNetwork=$bound (BT iAP2 ostaje aktivan!)",
                )
                val lp = connectivityManager.getLinkProperties(network)
                val info = buildNetworkInfo(network, ssid, lp)
                activeInfo.set(info)
                logger.log("WIFI", info.formatReportHr())
                if (deliveredConnected.compareAndSet(false, true)) {
                    onConnected(info)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (activeNetwork.get() != network) return
                val updated = buildNetworkInfo(network, ssid, linkProperties)
                val prev = activeInfo.getAndSet(updated)
                if (prev == null ||
                    prev.localIpv4 != updated.localIpv4 ||
                    prev.gatewayIpv4 != updated.gatewayIpv4 ||
                    prev.localIpv6Addresses != updated.localIpv6Addresses
                ) {
                    logger.log("WIFI", "Ažurirani LinkProperties za BMW Wi-Fi:\n${updated.formatReportHr()}")
                }
                if (deliveredConnected.compareAndSet(false, true)) {
                    onConnected(updated)
                }
            }

            override fun onUnavailable() {
                logger.log(
                    "WIFI",
                    "✗ onUnavailable: Spajanje na BMW Wi-Fi \"$ssid\" nije uspjelo ili je korisnik odbio sistemski dijalog",
                )
                onUnavailable("Spajanje na BMW Wi-Fi \"$ssid\" nije uspjelo / otkazano")
            }

            override fun onLost(network: Network) {
                if (activeNetwork.compareAndSet(network, null)) {
                    activeInfo.set(null)
                    connectivityManager.bindProcessToNetwork(null)
                    logger.log("WIFI", "BMW Wi-Fi mreža \"$ssid\" je izgubljena ($network) — proces odvezan s mreže")
                    onLost("Izgubljena veza s BMW Wi-Fi mrežom \"$ssid\"")
                }
            }
        }

        activeCallback.set(callback)
        logger.log(
            "WIFI",
            "Pozivam ConnectivityManager.requestNetwork za \"$ssid\" (ako se pojavi sistemski prozor, klikni Poveži / Connect)...",
        )
        try {
            connectivityManager.requestNetwork(request, callback, 45_000)
        } catch (e: SecurityException) {
            activeCallback.set(null)
            val msg = "Nedostaju Wi-Fi/mrežne dozvole za requestNetwork: ${e.message}"
            logger.log("WIFI", "GREŠKA: $msg")
            onUnavailable(msg)
        } catch (e: Exception) {
            activeCallback.set(null)
            val msg = "Greška pri requestNetwork: ${e.message}"
            logger.log("WIFI", "GREŠKA: $msg")
            onUnavailable(msg)
        }
    }

    /**
     * Releases the CarPlay Wi-Fi network request and unbinds the process network cleanly.
     */
    fun release() {
        activeCallback.getAndSet(null)?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        val hadNet = activeNetwork.getAndSet(null) != null
        activeInfo.set(null)
        if (hadNet) {
            runCatching { connectivityManager.bindProcessToNetwork(null) }
            logger.log("WIFI", "Oslobođena BMW Wi-Fi mreža i vraćena zadana mreža procesa")
        }
    }

    private fun buildNetworkInfo(
        network: Network,
        ssid: String,
        lp: LinkProperties?,
    ): CarNetworkInfo {
        val iface = lp?.interfaceName
        var localV4: String? = null
        val localV6 = ArrayList<String>()

        lp?.linkAddresses?.forEach { linkAddr ->
            val addr: InetAddress = linkAddr.address
            when (addr) {
                is Inet4Address -> if (localV4 == null) localV4 = addr.hostAddress
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

        // If DHCP didn't install a default gateway route (common on captive/no-internet APs),
        // also check DHCP server address on Android 11+ (LinkProperties.dhcpServerAddress).
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
            localIpv6Addresses = localV6,
            gatewayIpv4 = gwV4,
            gatewayIpv6 = gwV6,
            dnsServers = dns,
            mtu = mtu,
        )
    }
}
