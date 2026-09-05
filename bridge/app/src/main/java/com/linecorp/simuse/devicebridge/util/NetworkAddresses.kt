// SPDX-License-Identifier: Apache-2.0
package com.linecorp.simuse.devicebridge.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the device's LAN IPv4 address so the operator can point a
 * farm controller at `http://<ip>:<port>`.
 *
 * Deliberately uses `NetworkInterface` rather than
 * `WifiManager.getConnectionInfo().ipAddress`: the WifiManager path
 * needs `ACCESS_FINE_LOCATION` on modern Android (and returns 0 without
 * it), while enumerating interfaces needs no permission at all. It also
 * works for phones on Ethernet dongles / USB tethering, which some
 * racks use.
 */
object NetworkAddresses {

    /**
     * Best-guess LAN IPv4, or null when the device has no usable
     * non-loopback IPv4 (airplane mode, Wi-Fi off).
     */
    fun lanIpv4(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .map { Candidate(iface.name, it.hostAddress ?: "") }
            }
            .filter { it.address.isNotEmpty() }
        selectPreferred(candidates)
    } catch (_: Exception) {
        null
    }

    /** One interface-name / IPv4 pair. Split out so [selectPreferred] is pure. */
    data class Candidate(val interfaceName: String, val address: String)

    /**
     * Picks the address most likely to be the one the farm should dial.
     *
     * Order of preference:
     *   1. `wlan*` — the Wi-Fi radio, which is what a cable-free phone
     *      farm actually uses.
     *   2. `eth*` / `usb*` / `rndis*` — wired or tethered racks.
     *   3. Anything else, first come.
     *
     * `rmnet*` (cellular) is skipped entirely: a carrier-assigned
     * address is not reachable from the farm controller, and surfacing
     * it in the logs would send operators chasing a dead endpoint.
     */
    fun selectPreferred(candidates: List<Candidate>): String? {
        fun rank(name: String): Int = when {
            name.startsWith("wlan") -> 0
            name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis") -> 1
            else -> 2
        }
        return candidates
            .filterNot { it.interfaceName.startsWith("rmnet") || it.interfaceName.startsWith("ccmni") }
            .minByOrNull { rank(it.interfaceName) }
            ?.address
    }
}
