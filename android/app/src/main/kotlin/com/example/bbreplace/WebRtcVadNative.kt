package com.example.bbreplace

class WebRtcVadNative(
    mode: Int = MODE_VERY_AGGRESSIVE,
) : AutoCloseable {
    private var handle: Long = nativeCreate(mode)

    fun isSpeech(
        sampleRate: Int,
        frameBytes: ByteArray,
        frameSamples: Int,
    ): Boolean {
        if (handle == 0L) {
            return false
        }
        return nativeProcessPcm16(handle, sampleRate, frameBytes, frameSamples) == 1
    }

    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(mode: Int): Long

    private external fun nativeRelease(handle: Long)

    private external fun nativeProcessPcm16(
        handle: Long,
        sampleRate: Int,
        frameBytes: ByteArray,
        frameSamples: Int,
    ): Int

    companion object {
        const val MODE_QUALITY = 0
        const val MODE_LOW_BITRATE = 1
        const val MODE_AGGRESSIVE = 2
        const val MODE_VERY_AGGRESSIVE = 3

        init {
            System.loadLibrary("speech_repeater_native")
        }
    }
}
