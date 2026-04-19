package com.example.bbreplace

import android.content.Context
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

class RootTinycapCapture(
    context: Context,
    private val sampleRate: Int,
    private val pcmDevice: Int = 3,
) {
    private val appContext = context.applicationContext
    private val fifoFile = File(appContext.cacheDir, "root_bt_capture.wav")

    private var process: Process? = null
    private var inputStream: BufferedInputStream? = null

    fun start() {
        stop()
        prepareFifo()

        val script =
            """
            tinymix 2633 1 0 >/dev/null 2>&1 || true
            tinymix 2485 1 0 >/dev/null 2>&1 || true
            tinymix 1536 1 0 >/dev/null 2>&1 || true
            tinymix 317 1 >/dev/null 2>&1 || true
            tinycap '${fifoFile.absolutePath}' -D 0 -d $pcmDevice -c 1 -r $sampleRate -b 16
            """.trimIndent()

        val captureProcess =
            ProcessBuilder("su", "0", "sh", "-c", script)
                .redirectErrorStream(true)
                .start()

        if (captureProcess.waitFor(350L, TimeUnit.MILLISECONDS)) {
            val output = captureProcess.inputStream.bufferedReader().use { it.readText().trim() }
            throw IllegalStateException(
                if (output.isBlank()) "root 采集进程启动失败" else "root 采集进程启动失败: $output",
            )
        }

        process = captureProcess
        inputStream = BufferedInputStream(FileInputStream(fifoFile))
        skipWavHeader(inputStream ?: error("采集管道未打开"))
    }

    fun read(buffer: ByteArray): Int =
        inputStream?.read(buffer) ?: -1

    fun stop() {
        inputStream?.runCatching { close() }
        inputStream = null

        process?.runCatching {
            destroy()
            waitFor(200L, TimeUnit.MILLISECONDS)
            if (isAlive) {
                destroyForcibly()
            }
        }
        process = null

        if (fifoFile.exists()) {
            fifoFile.delete()
        }
    }

    companion object {
        fun isRootAvailable(): Boolean =
            runCatching {
                ProcessBuilder("su", "0", "sh", "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                    .run { waitFor(500L, TimeUnit.MILLISECONDS) && exitValue() == 0 }
            }.getOrDefault(false)

        private const val WAVE_HEADER_BYTES = 44
        private const val FIFO_MODE = 0x1B6
    }

    private fun prepareFifo() {
        if (fifoFile.exists()) {
            fifoFile.delete()
        }
        Os.mkfifo(fifoFile.absolutePath, FIFO_MODE)
    }

    private fun skipWavHeader(stream: BufferedInputStream) {
        val header = ByteArray(WAVE_HEADER_BYTES)
        var offset = 0
        while (offset < header.size) {
            val read = stream.read(header, offset, header.size - offset)
            if (read <= 0) {
                throw IllegalStateException("root 采集没有输出音频头")
            }
            offset += read
        }
    }
}
