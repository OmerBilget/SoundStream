package com.example.relay

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Utility class for network interface discovery and IP address retrieval.
 */
object NetworkUtils {

    /**
     * Retrieves the IPv4 address of the WiFi interface (typically "wlan0").
     * @return The IP address string or null if not found.
     */
    fun getWifiIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                // Search for typical WiFi interface naming patterns
                if (intf.name.contains("wlan", ignoreCase = true)) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            }
            null
        } catch (ex: Exception) {
            null
        }
    }

    /**
     * Retrieves the IPv4 address of the USB tethering interface (typically "rndis0" or "usb0").
     * @return The IP address string or null if not found.
     */
    fun getUsbIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                // Search for typical USB/RNDIS interface naming patterns
                if (intf.name.contains("rndis", ignoreCase = true) || intf.name.contains("usb", ignoreCase = true)) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            }
            null
        } catch (ex: Exception) {
            null
        }
    }

    /**
     * Checks if a USB tethering connection is active.
     */
    fun isUsbTetheringAvailable(): Boolean {
        return getUsbIpAddress() != null
    }

    /**
     * Convenience method to get the best available local IP address.
     * Prioritizes USB tethering over WiFi.
     */
    fun getLocalIpAddress(): String {
        return getUsbIpAddress() ?: getWifiIpAddress() ?: "No Connection"
    }
}
