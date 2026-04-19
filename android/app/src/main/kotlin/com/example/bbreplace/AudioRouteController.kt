package com.example.bbreplace

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class AudioRouteController(
    context: Context,
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var originalMode = AudioManager.MODE_NORMAL
    private var activeOutputRouteName = "手机扬声器"
    private var activated = false

    fun activateCommunicationRoute(preferBluetooth: Boolean): String {
        if (!activated) {
            originalMode = audioManager.mode
            activated = true
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false

        activeOutputRouteName =
            if (preferBluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activateModernRoute()
            } else if (preferBluetooth) {
                activateLegacyBluetoothSco()
            } else {
                "手机扬声器"
            }
        return activeOutputRouteName
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = originalMode
        activated = false
    }

    fun currentRouteName(): String = activeOutputRouteName

    fun ensureCommunicationRoute(preferBluetooth: Boolean): String =
        activateCommunicationRoute(preferBluetooth)

    fun findPreferredInputDevice(): AudioDeviceInfo? {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        preferredInputTypes().forEach { preferredType ->
            inputDevices.firstOrNull { it.type == preferredType }?.let { return it }
        }

        return inputDevices.firstOrNull()
    }

    fun findBluetoothInputDevice(): AudioDeviceInfo? {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        bluetoothInputTypes().forEach { preferredType ->
            inputDevices.firstOrNull { it.type == preferredType }?.let { return it }
        }
        return null
    }

    fun findBuiltinInputDevice(): AudioDeviceInfo? {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun activateModernRoute(): String {
        val devices = audioManager.availableCommunicationDevices
        preferredCommunicationTypes().forEach { preferredType ->
            val device = devices.firstOrNull { it.type == preferredType }
            if (device != null && audioManager.setCommunicationDevice(device)) {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                return outputDisplayName(device)
            }
        }
        return "手机扬声器"
    }

    private fun activateLegacyBluetoothSco(): String {
        val hasBluetoothSco =
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        if (!hasBluetoothSco) {
            return "手机麦克风"
        }

        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
        return "蓝牙通话耳机/音箱"
    }

    fun waitForBluetoothScoReady(timeoutMs: Long = 1800L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hasBluetoothInput = findBluetoothInputDevice() != null
            val communicationDevice =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.communicationDevice
                } else {
                    null
                }
            val communicationOnBluetooth =
                communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        communicationDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            @Suppress("DEPRECATION")
            val scoOn = audioManager.isBluetoothScoOn
            if (hasBluetoothInput && (communicationOnBluetooth || scoOn)) {
                return true
            }
            Thread.sleep(60L)
        }
        return findBluetoothInputDevice() != null
    }

    fun inputRouteLabel(device: AudioDeviceInfo?): String {
        if (device == null) {
            return "未知"
        }
        return inputDisplayName(device)
    }

    private fun preferredCommunicationTypes(): List<Int> {
        val types = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            types += AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        types += AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        types += AudioDeviceInfo.TYPE_WIRED_HEADSET
        types += AudioDeviceInfo.TYPE_USB_HEADSET
        return types
    }

    private fun preferredInputTypes(): List<Int> = preferredCommunicationTypes()

    private fun bluetoothInputTypes(): List<Int> {
        val types = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            types += AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        types += AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        return types
    }

    private fun inputDisplayName(device: AudioDeviceInfo): String =
        inputTypeLabel(device.type)
            ?: device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: "外接麦克风"

    private fun outputDisplayName(device: AudioDeviceInfo): String =
        outputTypeLabel(device.type)
            ?: device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: "外接扬声器"

    private fun inputTypeLabel(type: Int): String? =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦克风"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话麦克风"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳机麦克风"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦克风"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机麦克风"
            else -> null
        }

    private fun outputTypeLabel(type: Int): String? =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "手机听筒"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话耳机/音箱"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳机"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
            else -> null
        }
}
