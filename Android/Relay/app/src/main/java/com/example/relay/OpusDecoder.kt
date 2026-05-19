package com.example.relay

/**
 * JNI wrapper for the native C++ Opus decoding library.
 * Handles the lifecycle and data passing for the Opus decoder.
 */
class OpusDecoder {

    /**
     * Pointer to the native C++ decoder object. 
     * Managed by the native layer; do not modify manually.
     */
    private var nativePtr: Long = 0
    
    /**
     * Number of audio channels (e.g., 2 for Stereo).
     */
    private var channels: Int = 0

    /**
     * Initializes the native Opus decoder.
     * @param sampleRate The desired output sample rate (e.g., 48000).
     * @param channels The number of output channels (e.g., 2).
     * @return true if initialization was successful.
     */
    external fun init(sampleRate: Int, channels: Int): Boolean

    /**
     * Decodes an Opus-encoded data packet into PCM 16-bit bytes.
     * @param input The encoded Opus packet byte array.
     * @return A byte array containing decoded PCM data, or null on failure.
     */
    external fun decode(input: ByteArray): ByteArray?

    /**
     * Releases native resources allocated by the decoder.
     * Should be called when the decoder is no longer needed to prevent memory leaks.
     */
    external fun release()

    companion object {
        init {
            // Load the native JNI library
            System.loadLibrary("opusjni")
        }
    }
}
