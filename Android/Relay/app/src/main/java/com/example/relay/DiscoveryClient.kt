package com.example.relay

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Handles the secure discovery and handshake process with the PC server.
 * Uses UDP broadcast to find servers and HMAC-SHA256 for identity verification.
 */
object DiscoveryClient {
    private const val TAG = "DiscoveryClient"
    private const val DISCOVERY_PORT = 50505
    private const val DISCOVER_MSG = "SOUNDSTREAM_DISCOVER"
    private const val HERE_PREFIX = "SOUNDSTREAM_HERE"
    private const val HELLO_PREFIX = "HELLO"
    
    // Secret key for HMAC verification. Must match the PC server's key.
    private const val SECRET_KEY = "SUPER_SECRET_SOUNDSTREAM_KEY_2026"

    /**
     * Calculates the HMAC-SHA256 signature for the given data.
     */
    private fun calculateHMAC(data: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(SECRET_KEY.toByteArray(), "HmacSHA256")
            mac.init(secretKey)
            val hmacBytes = mac.doFinal(data.toByteArray())
            android.util.Base64.encodeToString(hmacBytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "HMAC calculation failed", e)
            ""
        }
    }

    /**
     * Executes the three-step discovery handshake:
     * 1. Broadcast "SOUNDSTREAM_DISCOVER" to the network.
     * 2. Receive and verify "SOUNDSTREAM_HERE" from a server.
     * 3. Send "HELLO" handshake with phone details back to the server.
     * 
     * @return The IP address of the discovered PC if successful, null otherwise.
     */
    suspend fun discoverAndHandshake(context: Context, phoneIp: String, audioPort: Int): String? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        var lock: WifiManager.MulticastLock? = null
        try {
            // Enable multicast reception for discovery
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifiManager.createMulticastLock("DiscoveryLock").apply {
                setReferenceCounted(true)
                acquire()
            }

            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 3000 // Timeout for server responses
            }

            // Step 1: Broadcast Discovery Message
            val discoverData = DISCOVER_MSG.toByteArray()
            val discoverPacket = DatagramPacket(
                discoverData,
                discoverData.size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            socket.send(discoverPacket)
            Log.d(TAG, "Discovery broadcast sent")

            // Step 2: Receive and verify "SOUNDSTREAM_HERE" response
            val receiveBuffer = ByteArray(1024)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            val receivedMsg = String(receivePacket.data, 0, receivePacket.length).trim()
            Log.d(TAG, "Received potential server response: $receivedMsg")

            val parts = receivedMsg.split("|")
            var pcIp: String? = null
            var receivedHmac: String? = null
            var dataToVerify: String? = null

            // Parse response and extract HMAC for verification
            if (parts.size == 3 && parts[0] == HERE_PREFIX) {
                pcIp = parts[1]
                receivedHmac = parts[2]
                dataToVerify = "${parts[0]}|${parts[1]}"
            } else if (parts.size == 2 && parts[0].startsWith(HERE_PREFIX)) {
                // Support legacy or slightly modified formats (e.g., prefix with space)
                val prefixPart = parts[0].split(" ")
                if (prefixPart.size == 2) {
                    pcIp = prefixPart[1]
                    receivedHmac = parts[1]
                    dataToVerify = parts[0]
                }
            }

            if (pcIp != null && receivedHmac != null && dataToVerify != null) {
                // Verify the server's identity using the shared secret
                val computedHmac = calculateHMAC(dataToVerify)
                
                if (computedHmac != receivedHmac) {
                    Log.e(TAG, "Server HMAC verification failed! Authentication mismatch.")
                    return@withContext null
                }

                // Step 3: Send secure handshake: HELLO|PhoneIP|AudioPort|HMAC
                val helloBase = "$HELLO_PREFIX|$phoneIp|$audioPort"
                val handshakeHmac = calculateHMAC(helloBase)
                val finalHandshakeMsg = "$helloBase|$handshakeHmac"
                
                val helloData = finalHandshakeMsg.toByteArray()
                val helloPacket = DatagramPacket(
                    helloData,
                    helloData.size,
                    InetAddress.getByName(pcIp),
                    DISCOVERY_PORT
                )
                socket.send(helloPacket)
                Log.d(TAG, "Secure handshake sent to server at $pcIp")
                return@withContext pcIp
            } else {
                Log.w(TAG, "Received message with invalid format: $receivedMsg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery process failed", e)
        } finally {
            socket?.close()
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release MulticastLock", e)
            }
        }
        null
    }
}
