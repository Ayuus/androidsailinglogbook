package com.ayuus.mysailinglogbook

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Finds the phone's own hotspot subnet via NetworkInterface enumeration -- no special permission
 * needed, confirmed on a real Samsung Galaxy S23 (spike 2, docs/android-app-plan.md). With the
 * hotspot on, its bridge interface (named "ap_br_swlan0" on this device; Android's own docs
 * mention "ap0"/"wlan1" as other common OEM names) shows up with a private IPv4 address --
 * distinguishable from cellular (point-to-point, excluded outright below) and from an idle wifi
 * radio (no IPv4 address at all while not connected to anything).
 */
object HotspotDetector {

    /** First three octets of the hotspot's own IPv4 address (e.g. "10.190.25."), or null if no
     * hotspot-like interface with a private IPv4 address is currently up. */
    fun detectSubnetPrefix(): String? {
        val candidates = allInterfaces()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { iface -> ipv4Addresses(iface).map { address -> iface.name to address } }
            .filter { (_, address) -> isPrivateIpv4(address) }

        // Prefer a name that looks like a hotspot/AP interface when more than one private-IPv4
        // candidate is up at once (e.g. also joined to a wifi router as a client) -- otherwise
        // just take whatever matched, there's normally exactly one.
        val preferred = candidates.firstOrNull { (name, _) -> name.contains("ap", ignoreCase = true) }
            ?: candidates.firstOrNull()

        val ip = preferred?.second?.hostAddress ?: return null
        val lastDot = ip.lastIndexOf('.')
        return if (lastDot > 0) ip.substring(0, lastDot + 1) else null
    }

    /**
     * Whether this phone's own hotspot is on right now -- stricter than [detectSubnetPrefix], which takes any
     * private IPv4 interface and so also answers for a phone that is simply connected to a wifi network
     * (found in practice: at home, on wifi, the boat mode started itself on every launch). Only an
     * interface named like a hotspot/AP counts ("ap_br_swlan0" on the S23, "ap0"/"swlan0" elsewhere); the
     * wifi client interface (wlan0) does not.
     */
    fun isHotspotUp(): Boolean = allInterfaces()
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
        .filter { it.name.contains("ap", ignoreCase = true) || it.name.contains("swlan", ignoreCase = true) }
        .any { iface -> ipv4Addresses(iface).any { isPrivateIpv4(it) } }

    private fun allInterfaces(): List<NetworkInterface> {
        // getNetworkInterfaces() is documented to return null (not an empty Enumeration) when
        // none are found, and inetAddresses below did the same for at least one interface on the
        // real S23 -- Collections.list() throws on a null Enumeration, found in practice.
        val raw = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        return Collections.list(raw)
    }

    private fun ipv4Addresses(iface: NetworkInterface): List<Inet4Address> {
        val raw = iface.inetAddresses ?: return emptyList()
        return Collections.list(raw).filterIsInstance<Inet4Address>()
    }

    private fun isPrivateIpv4(address: Inet4Address): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
    }
}
