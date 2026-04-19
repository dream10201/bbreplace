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
    private var activeRouteName = "手机麦克风"
    private var activated = false

    fun activateCommunicationRoute(): String {
        if (!activated) {
            originalMode = audioManager.mode
            activated = true
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false

        activeRouteName =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activateModernRoute()
            } else {
                activateLegacyBluetoothSco()
            }
        return activeRouteName
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

    fun currentRouteName(): String = activeRouteName

    fun ensureCommunicationRoute(): String = activateCommunicationRoute()

    fun findPreferredInputDevice(): AudioDeviceInfo? {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        preferredTypes().forEach { preferredType ->
            inputDevices.firstOrNull { it.type == preferredType }?.let { return it }
        }

        return inputDevices.firstOrNull()
    }

    private fun activateModernRoute(): String {
        val devices = audioManager.availableCommunicationDevices
        preferredTypes().forEach { preferredType ->
            val device = devices.firstOrNull { it.type == preferredType }
            if (device != null && audioManager.setCommunicationDevice(device)) {
                return displayName(device)
            }
        }
        return "手机麦克风"
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
        return "蓝牙通话麦克风"
    }

    fun routeLabel(device: AudioDeviceInfo?): String {
        if (device == null) {
            return activeRouteName
        }
        return displayName(device)
    }

    private fun preferredTypes(): List<Int> {
        val types = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            types += AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        types += AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            types += AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        types += AudioDeviceInfo.TYPE_WIRED_HEADSET
        types += AudioDeviceInfo.TYPE_USB_HEADSET
        return types
    }

    private fun displayName(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeLabel(device.type)

    private fun typeLabel(type: Int): String =
        when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话麦克风"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦克风"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机麦克风"
            else -> "外接麦克风"
        }
}
