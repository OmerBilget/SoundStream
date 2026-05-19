package com.example.relay

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * AudioServiceOpus is a foreground service responsible for receiving Opus-encoded audio
 * packets over UDP, decoding them using a native Opus decoder, and playing the PCM data
 * through an AudioTrack.
 */
class AudioServiceOpus : Service() {

    companion object {
        private const val TAG = "AudioServiceOpus"
        const val EXTRA_PORT = "extra_port"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "opus_audio"
        private const val HEADER_SIZE = 12

        // Observables for the UI to monitor state and audio levels
        val amplitude = MutableStateFlow(0f)
        val isReceiving = MutableStateFlow(false)
        val isRunning = MutableStateFlow(false)
    }

    private var port = 5005
    private var isStarted = false
    private val running = AtomicBoolean(true)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Buffering and synchronization
    private val packetMap = TreeMap<Long, ByteArray>()
    private var nextExpectedSeq = -1L
    private var lastValidPacketTime = 0L

    private var socket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private var decoder: OpusDecoder? = null

    // Jitter buffer and synchronization tuning constants
    private val MAX_BUFFER = 120      // Max packets to hold in memory
    private val RESYNC_WINDOW = 20    // Max gap allowed before forced resync
    private val STALL_RESET_MS = 1500 // Time without valid audio before resetting state

    override fun onCreate() {
        super.onCreate()
        // Promote service to foreground to prevent the OS from killing it
        startForeground(NOTIF_ID, createNotification())

        // Initialize the native Opus decoder
        decoder = OpusDecoder().apply {
            if (!init(SAMPLE_RATE, CHANNELS)) {
                Log.e(TAG, "Opus initialization failed")
                stopSelf()
                return
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var newPort = intent?.getIntExtra(EXTRA_PORT, 5005) ?: 5005
        if (newPort !in 1024..65535) {
            Log.w(TAG, "Invalid port $newPort provided, falling back to 5005")
            newPort = 5005
        }
        
        if (!isStarted) {
            Log.i(TAG, "Starting Audio Service on port $newPort")
            port = newPort
            isStarted = true
            running.set(true)
            isRunning.value = true

            // Keep the CPU awake for high-priority audio processing
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Relay:AudioWakeLock").apply {
                acquire()
            }

            synchronized(packetMap) {
                packetMap.clear()
                nextExpectedSeq = -1L
            }
            
            startNetworkThread()
            startAudioThread()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Shutting down Audio Service")
        running.set(false)
        isStarted = false
        socket?.close()

        // Release WiFi lock
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null

        // Release CPU wake lock
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        // Clean up native decoder resources
        decoder?.release()

        // Clean up audio hardware resources
        audioTrack?.run {
            try {
                stop()
            } catch (e: Exception) { /* Ignore */ }
            release()
        }

        // Notify UI that the service has stopped
        amplitude.value = 0f
        isReceiving.value = false
        isRunning.value = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /**
     * Re-initializes the native decoder if a stall or fatal error is detected.
     */
    private fun recreateDecoder() {
        try {
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release decoder", e)
        }

        decoder = OpusDecoder().apply {
            val ok = init(SAMPLE_RATE, CHANNELS)
            if (!ok) Log.e(TAG, "Failed to re-initialize native decoder")
        }
    }

    /**
     * Handles incoming UDP packets on a dedicated network thread.
     */
    private fun startNetworkThread() {
        thread(name = "udp-network-thread") {
            // Set kernel-level priority for network packet handling
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            // Acquire WiFi low-latency mode to reduce jitter from power management
            val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Relay:LowLatencyLock").apply {
                acquire()
            }

            try {
                val safePort = if (port in 1024..65535) port else 5005
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    // Large receive buffer to handle network bursts and prevent packet drops
                    receiveBufferSize = 1024 * 1024 
                    // Traffic Class 0x10 (IPTOS_LOWDELAY) for IP-layer prioritization
                    trafficClass = 0x10 
                    bind(InetSocketAddress("0.0.0.0", safePort))
                }
                socket = s
                Log.i(TAG, "UDP Socket bound to port ${s.localPort}")

                val buffer = ByteArray(8192) // Supports jumbo frames and high bitrates
                var lastPacketReceivedTime = 0L

                // Watchdog thread to update the 'isReceiving' state for the UI
                thread(name = "network-watchdog") {
                    while (running.get()) {
                        isReceiving.value = (System.currentTimeMillis() - lastPacketReceivedTime) < 2000
                        try {
                            Thread.sleep(500)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }

                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)

                    try {
                        s.receive(packet)
                    } catch (e: SocketException) {
                        if (!running.get()) break
                        throw e
                    }

                    val now = System.currentTimeMillis()
                    lastPacketReceivedTime = now
                    lastValidPacketTime = now

                    val data = packet.data.copyOf(packet.length)
                    if (data.size < HEADER_SIZE) continue

                    // Extract sequence number from the 12-byte header
                    val header = ByteBuffer.wrap(data, 0, HEADER_SIZE)
                        .order(ByteOrder.LITTLE_ENDIAN)

                    val seq = header.int.toLong() and 0xFFFFFFFFL

                    // Insert into jitter buffer
                    synchronized(packetMap) {
                        packetMap[seq] = data
                        // Evict oldest packets if the buffer overflows
                        if (packetMap.size > MAX_BUFFER) {
                            packetMap.pollFirstEntry()
                        }
                    }
                }

            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Network thread exception", e)
                }
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    /**
     * Handles audio decoding and playback on a dedicated high-priority thread.
     */
    private fun startAudioThread() {
        thread(name = "audio-playback-thread") {
            // Highest possible priority to prevent audio underruns
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // Configure AudioTrack for low-latency performance path
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            audioTrack?.play()

            // Buffer used for silence during keep-alive or stalls
            val silent = ShortArray(960 * 2)

            while (running.get()) {
                var packet: ByteArray? = null

                synchronized(packetMap) {
                    if (packetMap.isNotEmpty()) {
                        if (nextExpectedSeq == -1L) {
                            // Initial buffering: Wait for a small burst to ensure stable playback
                            if (packetMap.size >= 5) {
                                packetMap.pollFirstEntry()?.let { entry ->
                                    packet = entry.value
                                    nextExpectedSeq = entry.key + 1
                                }
                            }
                        } else {
                            // Sequential delivery from jitter buffer
                            packet = packetMap.remove(nextExpectedSeq)
                            if (packet != null) {
                                nextExpectedSeq++
                            } else {
                                // Packet missing: Check if we should skip ahead to stay live
                                val first = try { packetMap.firstKey() } catch (e: Exception) { null }
                                if (first != null && first > nextExpectedSeq) {
                                    if (first - nextExpectedSeq > RESYNC_WINDOW) {
                                        Log.w(TAG, "Major sequence gap ($nextExpectedSeq -> $first), resyncing...")
                                    } else {
                                        Log.w(TAG, "Packet loss: jumping from $nextExpectedSeq to $first")
                                    }
                                    nextExpectedSeq = first
                                    packet = packetMap.remove(nextExpectedSeq)
                                    if (packet != null) {
                                        nextExpectedSeq++
                                        lastValidPacketTime = System.currentTimeMillis()
                                    }
                                } else if (first != null && first < nextExpectedSeq) {
                                    // Handle sequence wrap-around or massive jitter
                                    Log.w(TAG, "Old packets or wrap-around detected, resetting sync")
                                    nextExpectedSeq = -1L
                                }
                            }
                        }
                    }
                }

                if (packet != null) {
                    try {
                        // Decode the packet and write to the audio hardware
                        val pcm = decoder?.decode(packet!!)
                        if (pcm != null) {
                            audioTrack?.write(pcm, 0, pcm.size)
                            lastValidPacketTime = System.currentTimeMillis()

                            // Calculate peak amplitude for the UI visualizer
                            var max = 0
                            for (i in 0 until pcm.size - 1 step 2) {
                                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
                                val a = abs(sample.toInt())
                                if (a > max) max = a
                            }
                            amplitude.value = max / 32768f
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Decoding error - packet skipped", e)
                    }
                } else {
                    // No data available: Write silence to prevent AudioTrack from stopping
                    audioTrack?.write(silent, 0, silent.size)
                    
                    // Reset sync if we've been stalled for too long
                    val now = System.currentTimeMillis()
                    if (now - lastValidPacketTime > STALL_RESET_MS) {
                        Log.w(TAG, "Audio stall detected, resetting synchronization state")
                        recreateDecoder()
                        nextExpectedSeq = -1L
                        lastValidPacketTime = now
                    }
                    Thread.sleep(2)
                }
            }
            amplitude.value = 0f
        }
    }

    /**
     * Creates the persistent notification required for foreground services.
     */
    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Real-time Audio Streaming",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SoundStream Active")
            .setContentText("Receiving high-quality audio on port $port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
