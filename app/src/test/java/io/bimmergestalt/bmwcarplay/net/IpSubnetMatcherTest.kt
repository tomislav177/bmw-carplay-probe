package io.bimmergestalt.bmwcarplay.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class IpSubnetMatcherTest {

    @Test
    fun matchesStandardSlash24Ipv4Subnets() {
        assertTrue(IpSubnetMatcher.sameIpv4Subnet("192.168.43.20", "192.168.43.1", 24))
        assertFalse(IpSubnetMatcher.sameIpv4Subnet("192.168.43.20", "192.168.44.1", 24))
    }

    @Test
    fun matchesAutoIpLinkLocal169254Slash16AcrossDifferentThirdOctets() {
        // Common on CarPlay head units (RFC 3927 link-local AutoIP): car & phone differ in 3rd octet!
        assertTrue(IpSubnetMatcher.sameIpv4Subnet("169.254.98.76", "169.254.12.34", 16))
        // Even if prefixLength wasn't reported (0 / 24), two 169.254.x.y hosts share L2 link-local
        assertTrue(IpSubnetMatcher.sameIpv4Subnet("169.254.98.76", "169.254.12.34", 24))
        assertFalse(IpSubnetMatcher.sameIpv4Subnet("169.254.98.76", "192.168.1.1", 16))
    }

    @Test
    fun matchesArbitraryCidrPrefixLengths() {
        assertTrue(IpSubnetMatcher.sameIpv4Subnet("172.16.20.5", "172.16.21.1", 20))
        assertFalse(IpSubnetMatcher.sameIpv4Subnet("172.16.20.5", "172.16.40.1", 20))
        assertTrue(IpSubnetMatcher.sameIpv4Subnet("10.174.189.2", "10.174.189.1", 24))
    }

    @Test
    fun matchesIpv6LinkLocalAndSlash64Prefix() {
        val linkLocal = InetAddress.getByName("fe80::f830:2ff:fefc:79a4")
        assertTrue(
            IpSubnetMatcher.belongsToCarSubnet(
                candidate = linkLocal,
                localIpv4 = "192.168.1.2",
                localIpv4PrefixLength = 24,
                localIpv6Addresses = listOf("fe80::7c7b:bfff:fe74:4067%wlan0"),
            ),
        )

        val sameUla = InetAddress.getByName("fd12:3456:789a:1::1")
        val otherUla = InetAddress.getByName("fd12:3456:789a:2::1")
        assertTrue(
            IpSubnetMatcher.belongsToCarSubnet(
                candidate = sameUla,
                localIpv4 = null,
                localIpv4PrefixLength = 24,
                localIpv6Addresses = listOf("fd12:3456:789a:1::42"),
            ),
        )
        assertFalse(
            IpSubnetMatcher.belongsToCarSubnet(
                candidate = otherUla,
                localIpv4 = null,
                localIpv4PrefixLength = 24,
                localIpv6Addresses = listOf("fd12:3456:789a:1::42"),
            ),
        )
    }
}
