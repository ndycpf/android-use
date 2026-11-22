package com.androiduse.core

import java.net.Inet4Address
import java.net.NetworkInterface

enum class BindMode {
    LOOPBACK,
    TAILSCALE,
    ALL_INTERFACES,
}

/**
 * Pure network helpers for detecting Tailscale CGNAT IPv4 addresses (`100.64.0.0/10`)
 * and determining whether a bind host requires Bearer token authentication.
 */
object NetworkAddressHelper {

    fun isLoopbackHost(host: String): Boolean =
        host == "127.0.0.1" || host == "localhost" || host == "::1"

    /**
     * Returns true if [ipv4Bytes] falls within RFC 6598 / Tailscale CGNAT space (`100.64.0.0/10`).
     */
    fun isTailscaleIpv4(ipv4Bytes: ByteArray): Boolean {
        if (ipv4Bytes.size != 4) return false
        val first = ipv4Bytes[0].toInt() and 0xFF
        val second = ipv4Bytes[1].toInt() and 0xFF
        return first == 100 && (second and 0xC0) == 64
    }

    /**
     * Scans active local network interfaces for a Tailscale IPv4 address (`100.64.0.0/10`).
     */
    fun detectTailscaleIpv4(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && isTailscaleIpv4(addr.address)) {
                        return@runCatching addr.hostAddress
                    }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Detects a non-loopback LAN IPv4 address (excluding Tailscale CGNAT) for display hints.
     */
    fun detectLanIpv4(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !isTailscaleIpv4(addr.address)) {
                        return@runCatching addr.hostAddress
                    }
                }
            }
            null
        }.getOrNull()
    }
}
