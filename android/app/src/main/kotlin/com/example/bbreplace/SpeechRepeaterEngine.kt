package com.example.bbreplace

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.sqrt

class SpeechRepeaterEngine(
    context: Context,
    private val onStatus: (Map<String, Any>) -> Unit,
) {
    private val appContext = context.applicationContext
    @Volatile
    private var running = false

    private var workerThread: Thread? = null

    private val sampleRate = 16_000
    private val frameSamples = 320
    private val frameBytes = frameSamples * 2
    private val startFramesRequired = 2
    private val endSilenceFrames = 52
    private val minSpeechFrames = 8
    private val maxSpeechFrames = 3000
    private val preRollFrames = 22
    private val endPaddingFrames = 8
    private val maxLeadingSilenceFrames = 3
    private val maxTrailingSilenceFrames = 4
    private val maxInternalSilenceFrames = 12
    private val startThresholdFloor = 520.0
    private val startThresholdRatio = 2.0
    private val continueThresholdRatio = 1.35
    private val minNoiseFloor = 120.0
    private val maxNoiseFloor = 3200.0
    private val routeCheckIntervalFrames = 100
    private val postPlaybackSuppressMs = 180L
    private val playbackKeepAliveIntervalMs = 120L
    private val routeController = AudioRouteController(appContext)
    private val runStateStore = RunStateStore(appContext)

    fun start() {
        if (running) {
            return
        }
        running = true
        workerThread = Thread(::recordLoop, "speech-repeater-engine").also { it.start() }
    }

    fun stop() {
        running = false
        workerThread?.interrupt()
        workerThread = null
    }

    private fun recordLoop() {
        val preferBluetooth = shouldPreferBluetoothInput()
        var routeName = routeController.activateCommunicationRoute(preferBluetooth)
        if (preferBluetooth) {
            routeController.waitForBluetoothScoReady()
            routeName = routeController.currentRouteName()
        }
        recordLoopWithAudioRecord(routeName, preferBluetooth)
    }

    private fun recordLoopWithAudioRecord(
        initialRouteName: String,
        preferBluetooth: Boolean,
    ) {
        var routeName = initialRouteName
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            onStatus(status("error", "无法初始化录音缓冲区", false))
            running = false
            return
        }

        val recorder =
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuffer, frameBytes * 8))
                .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onStatus(status("error", "录音器初始化失败", false))
            running = false
            return
        }

        var preferredInputDevice = selectInputDevice()
        if (preferredInputDevice != null) {
            recorder.preferredDevice = preferredInputDevice
        }

        val frameBuffer = ByteArray(frameBytes)
        val preRoll = ArrayDeque<ByteArray>()
        val captureFrames = ArrayList<ByteArray>()
        val playbackTrack = createPlaybackTrack()
        var noiseFloor = 280.0
        var voicedFrames = 0
        var silentFrames = 0
        var speechFrames = 0
        var capturing = false
        var suppressionUntilMs = 0L
        var lastUtteranceMs = 0
        var frameCounter = 0
        var lastRouteEnsureMs = 0L
        var lastKeepAliveWriteMs = 0L

        try {
            recorder.startRecording()
            playbackTrack.play()
            val currentInputRoute = currentRecorderRoute(recorder, preferredInputDevice, routeName)
            onStatus(
                status(
                    "listening",
                    "持续监听中 · $currentInputRoute",
                    true,
                    lastUtteranceMs,
                    inputRoute = currentInputRoute,
                    outputRoute = routeName,
                ),
            )

            while (running && !Thread.currentThread().isInterrupted) {
                frameCounter += 1
                if (frameCounter >= routeCheckIntervalFrames) {
                    frameCounter = 0
                    val nowRouteMs = System.currentTimeMillis()
                    val currentRoute = currentRecorderRoute(recorder, preferredInputDevice, routeName)
                    val needsBluetoothRecovery =
                        preferBluetooth && !currentRoute.contains("蓝牙")
                    if (needsBluetoothRecovery && nowRouteMs - lastRouteEnsureMs >= 2_000L) {
                        lastRouteEnsureMs = nowRouteMs
                        routeName = routeController.ensureCommunicationRoute(preferBluetooth)
                        if (preferBluetooth) {
                            routeController.waitForBluetoothScoReady(1200L)
                        }
                        preferredInputDevice = refreshPreferredDevice(recorder, preferredInputDevice)
                    } else if (!needsBluetoothRecovery) {
                        preferredInputDevice = refreshPreferredDevice(recorder, preferredInputDevice)
                    }
                }

                val read = recorder.read(frameBuffer, 0, frameBuffer.size)
                if (read != frameBuffer.size) {
                    continue
                }

                val nowMs = System.currentTimeMillis()
                val frame = frameBuffer.copyOf()
                val rms = calculateRms(frame)

                if (preferBluetooth) {
                    lastKeepAliveWriteMs =
                        keepPlaybackActive(
                            audioTrack = playbackTrack,
                            nowMs = nowMs,
                            lastWriteMs = lastKeepAliveWriteMs,
                        )
                }

                if (nowMs < suppressionUntilMs) {
                    noiseFloor = recoverNoiseFloor(noiseFloor)
                    preRoll.addLast(frame)
                    trimPreRoll(preRoll)
                    continue
                }

                val startThreshold = max(startThresholdFloor, noiseFloor * startThresholdRatio)
                val continueThreshold = max(startThresholdFloor * 0.6, noiseFloor * continueThresholdRatio)
                val isVoiced = rms >= if (capturing) continueThreshold else startThreshold

                if (!capturing) {
                    noiseFloor = updateNoiseFloor(current = rms, floor = noiseFloor)
                    preRoll.addLast(frame)
                    trimPreRoll(preRoll)

                    voicedFrames = if (isVoiced) voicedFrames + 1 else max(0, voicedFrames - 1)
                    if (voicedFrames >= startFramesRequired) {
                        capturing = true
                        silentFrames = 0
                        speechFrames = 0
                        captureFrames.clear()
                        captureFrames.addAll(preRoll)
                        preRoll.clear()
                        onStatus(
                            status(
                                "capturing",
                                "检测到讲话，正在录制 · ${currentRecorderRoute(recorder, preferredInputDevice, routeName)}",
                                true,
                                lastUtteranceMs,
                                inputRoute = currentRecorderRoute(recorder, preferredInputDevice, routeName),
                                outputRoute = routeName,
                            ),
                        )
                    }
                    continue
                }

                captureFrames.add(frame)
                speechFrames += 1

                silentFrames = if (isVoiced) 0 else silentFrames + 1
                if (
                    speechFrames >= maxSpeechFrames ||
                    (speechFrames >= minSpeechFrames && silentFrames >= endSilenceFrames)
                ) {
                    repeat(endPaddingFrames) {
                        captureFrames.add(ByteArray(frameBytes))
                    }
                    val compactedFrames =
                        compactSilenceFrames(
                            frames = captureFrames,
                            noiseFloor = noiseFloor,
                        )
                    val utterance = flattenFrames(compactedFrames)
                    lastUtteranceMs = utterance.size / 2 * 1000 / sampleRate
                    capturing = false
                    voicedFrames = 0
                    silentFrames = 0
                    speechFrames = 0
                    captureFrames.clear()
                    preRoll.clear()

                    if (utterance.isNotEmpty()) {
                        routeName = routeController.currentRouteName()
                        preferredInputDevice = refreshPreferredDevice(recorder, preferredInputDevice)
                        val currentInputRoute = currentRecorderRoute(recorder, preferredInputDevice, routeName)
                        onStatus(
                            status(
                                "playing",
                                "回放刚才的声音 · $routeName",
                                true,
                                lastUtteranceMs,
                                inputRoute = currentInputRoute,
                                outputRoute = routeName,
                            ),
                        )
                        playOnce(playbackTrack, utterance)
                        suppressionUntilMs = System.currentTimeMillis() + postPlaybackSuppressMs
                        noiseFloor = recoverNoiseFloor(noiseFloor)
                    }

                    routeName = routeController.currentRouteName()
                    preferredInputDevice = refreshPreferredDevice(recorder, preferredInputDevice)
                    val currentInputRoute = currentRecorderRoute(recorder, preferredInputDevice, routeName)
                    onStatus(
                        status(
                            "listening",
                            "持续监听中 · $currentInputRoute",
                            true,
                            lastUtteranceMs,
                            inputRoute = currentInputRoute,
                            outputRoute = routeName,
                        ),
                    )
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Throwable) {
            onStatus(
                status(
                    "error",
                    error.message ?: "监听异常中断",
                    false,
                    lastUtteranceMs,
                    inputRoute = "未知",
                    outputRoute = routeController.currentRouteName(),
                ),
            )
        } finally {
            running = false
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
            playbackTrack.stop()
            playbackTrack.release()
            routeController.release()
            onStatus(
                status(
                    "idle",
                    "已停止",
                    false,
                    lastUtteranceMs,
                    inputRoute = "未知",
                    outputRoute = "未知",
                ),
            )
        }
    }


    private fun createPlaybackTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            max(minBuffer, frameBytes * 16),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    private fun playOnce(audioTrack: AudioTrack, data: ByteArray): Long {
        return try {
            if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.pause()
            }
            audioTrack.flush()
            audioTrack.play()
            audioTrack.write(data, 0, data.size)
            val durationMs = data.size / 2 * 1000L / sampleRate
            Thread.sleep(durationMs + 80L)
            durationMs
        } finally {
            audioTrack.flush()
        }
    }

    private fun keepPlaybackActive(
        audioTrack: AudioTrack,
        nowMs: Long,
        lastWriteMs: Long,
    ): Long {
        if (nowMs - lastWriteMs < playbackKeepAliveIntervalMs) {
            return lastWriteMs
        }
        val silence = ByteArray(frameBytes)
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play()
        }
        audioTrack.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
        return nowMs
    }

    private fun calculateRms(frame: ByteArray): Double {
        var sum = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < frame.size) {
            val low = frame[index].toInt() and 0xFF
            val high = frame[index + 1].toInt()
            val sample = (high shl 8) or low
            sum += sample.toDouble() * sample.toDouble()
            sampleCount += 1
            index += 2
        }
        return if (sampleCount == 0) 0.0 else sqrt(sum / sampleCount)
    }

    private fun updateNoiseFloor(current: Double, floor: Double): Double {
        if (current <= 0.0) {
            return floor
        }
        val clamped = current.coerceIn(minNoiseFloor, maxNoiseFloor)
        return floor * 0.92 + clamped * 0.08
    }

    private fun recoverNoiseFloor(floor: Double): Double =
        max(minNoiseFloor, floor * 0.88)

    private fun trimPreRoll(preRoll: ArrayDeque<ByteArray>) {
        while (preRoll.size > preRollFrames) {
            preRoll.removeFirst()
        }
    }

    private fun flattenFrames(frames: List<ByteArray>): ByteArray {
        val totalSize = frames.sumOf { it.size }
        val merged = ByteArray(totalSize)
        var offset = 0
        frames.forEach { frame ->
            frame.copyInto(merged, destinationOffset = offset)
            offset += frame.size
        }
        return merged
    }

    private fun compactSilenceFrames(
        frames: List<ByteArray>,
        noiseFloor: Double,
    ): List<ByteArray> {
        if (frames.isEmpty()) {
            return frames
        }

        val silenceThreshold = max(startThresholdFloor * 0.45, noiseFloor * 1.1)
        val result = ArrayList<ByteArray>(frames.size)
        var silentRun = 0

        frames.forEachIndexed { index, frame ->
            val isSilent = calculateRms(frame) < silenceThreshold
            if (!isSilent) {
                silentRun = 0
                result.add(frame)
                return@forEachIndexed
            }

            silentRun += 1
            val remaining = frames.size - index - 1
            val trailingSilent = remaining < maxTrailingSilenceFrames
            val limit =
                when {
                    result.isEmpty() -> maxLeadingSilenceFrames
                    trailingSilent -> maxTrailingSilenceFrames
                    else -> maxInternalSilenceFrames
                }

            if (silentRun <= limit) {
                result.add(frame)
            }
        }

        return result.ifEmpty { frames.takeLast(minOf(frames.size, maxTrailingSilenceFrames)) }
    }

    private fun refreshPreferredDevice(
        recorder: AudioRecord,
        currentPreferred: AudioDeviceInfo?,
    ): AudioDeviceInfo? {
        val routedDevice = recorder.routedDevice
        val bestDevice = selectInputDevice()
        if (
            bestDevice != null &&
            currentPreferred?.id != bestDevice.id &&
            routedDevice?.id != bestDevice.id
        ) {
            recorder.preferredDevice = bestDevice
            return bestDevice
        }
        return currentPreferred ?: bestDevice
    }

    private fun selectInputDevice(): AudioDeviceInfo? =
        when (runStateStore.getInputMode()) {
            RunStateStore.INPUT_MODE_BLUETOOTH ->
                routeController.findBluetoothInputDevice()
                    ?: routeController.findBuiltinInputDevice()
                    ?: routeController.findPreferredInputDevice()

            RunStateStore.INPUT_MODE_PHONE ->
                routeController.findBuiltinInputDevice()
                    ?: routeController.findPreferredInputDevice()

            else ->
                routeController.findBluetoothInputDevice()
                    ?: routeController.findBuiltinInputDevice()
                    ?: routeController.findPreferredInputDevice()
        }

    private fun shouldPreferBluetoothInput(): Boolean =
        runStateStore.getInputMode() != RunStateStore.INPUT_MODE_PHONE


    private fun currentRecorderRoute(
        recorder: AudioRecord,
        preferredDevice: AudioDeviceInfo?,
        fallback: String,
    ): String {
        recorder.routedDevice?.let { return routeController.inputRouteLabel(it) }
        preferredDevice?.let { return routeController.inputRouteLabel(it) }
        return if (fallback.contains("蓝牙")) "蓝牙通话麦克风" else "手机麦克风"
    }

    private fun status(
        state: String,
        message: String,
        isRunning: Boolean,
        lastUtteranceMs: Int = 0,
        inputRoute: String = "未知",
        outputRoute: String = "未知",
    ): Map<String, Any> = mapOf(
        "state" to state,
        "message" to message,
        "isRunning" to isRunning,
        "lastUtteranceMs" to lastUtteranceMs,
        "inputRoute" to inputRoute,
        "outputRoute" to outputRoute,
    )
}
