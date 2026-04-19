package com.example.bbreplace

class RnNoiseNative : AutoCloseable {
    private var handle: Long = nativeCreate()

    val isReady: Boolean
        get() = handle != 0L

    fun processInPlace(frameBytes: ByteArray): Boolean {
        if (handle == 0L) {
            return false
        }
        return nativeProcess16kPcm16InPlace(handle, frameBytes)
    }

    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long

    private external fun nativeRelease(handle: Long)

    private external fun nativeProcess16kPcm16InPlace(
        handle: Long,
        frameBytes: ByteArray,
    ): Boolean

    companion object {
        init {
            System.loadLibrary("speech_repeater_native")
        }
    }
}
