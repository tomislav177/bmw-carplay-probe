package io.bimmergestalt.bmwcarplay.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Pure-Kotlin subnet matching utility for validating mDNS-discovered AirPlay endpoints
 * against the car Wi-Fi interface's assigned IPv4 CIDR / AutoIP (169.254.0.0/16) or IPv6 subnet.
 */
object IpSubnetMatcher {

    /**
     * Returns true if [candidate] belongs to the same subnet as the car Wi-Fi interface.
     *
     * Handles:
     * - RFC 3927 IPv4 link-local (`169.254.0.0/16`) commonly used by CarPlay head units
     * - Arbitrary IPv4 CIDR prefix lengths from Android `LinkProperties` (`/8`, `/16`, `/20`, `/24`, etc.)
     * - IPv6 link-local (`fe80::/10`)
     * - IPv6 ULA / global `/64` prefix matching
     */
    fun belongsToCarSubnet(
        candidate: InetAddress,
        localIpv4: String?,
        localIpv4PrefixLength: Int,
        localIpv6Addresses: List<String>,
    ): Boolean = when (candidate) {
        is Inet4Address -> {
            if (localIpv4.isNullOrBlank()) {
                true
            } else {
                val candV4 = candidate.hostAddress.orEmpty()
                sameIpv4Subnet(localIpv4, candV4, localIpv4PrefixLength)
            }
        }

        is Inet6Address -> {
            if (candidate.isLinkLocalAddress) {
                true
            } else {
                val candPrefix = normalizeIpv6Prefix64(candidate.hostAddress.orEmpty())
                candPrefix != null && localIpv6Addresses.any { local ->
                    normalizeIpv6Prefix64(local) == candPrefix
                }
            }
        }

        else -> false
    }

    /**
     * Checks whether two dotted-quad IPv4 strings fall within the same subnet mask.
     * If [prefixLength] is invalid (<= 0 or > 32), defaults to `/16` for `169.254.x.y` AutoIP
     * and `/24` for all other ranges.
     */
    fun sameIpv4Subnet(localIpv4: String, candidateIpv4: String, prefixLength: Int): Boolean {
        val localInt = parseIpv4ToInt(localIpv4) ?: return false
        val candInt = parseIpv4ToInt(candidateIpv4) ?: return false

        // Both in RFC 3927 link-local 169.254.0.0/16 always share the car link-local L2 domain
        val linkLocalMask = 0xFFFF0000.toInt()
        val linkLocalNet = (169 shl 24) or (254 shl 16)
        if ((localInt and linkLocalMask) == linkLocalNet &&
            (candInt and linkLocalMask) == linkLocalNet
        ) {
            return true
        }

        val effectivePrefix = when {
            prefixLength in 1..32 -> prefixLength
            (localInt and linkLocalMask) == linkLocalNet -> 16
            else -> 24
        }
        val mask = if (effectivePrefix == 32) {
            -1
        } else {
            (-1 shl (32 - effectivePrefix))
        }
        return (localInt and mask) == (candInt and mask)
    }

    internal fun parseIpv4ToInt(ip: String): Int? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var acc = 0
        for (p in parts) {
            val octet = p.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            acc = (acc shl 8) or octet
        }
        return acc
    }

    private fun normalizeIpv6Prefix64(raw: String): ByteArrayWrapper? {
        val clean = raw.trim().substringBefore('%')
        if (clean.isEmpty()) return null
        val bytes = runCatching { InetAddress.getByName(clean).address }.getOrNull() ?: return null
        if (bytes.size != 16) return null
        return ByteArrayWrapper(bytes.copyOfRange(0, 8))
    }

    private class ByteArrayWrapper(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is ByteArrayWrapper && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}
