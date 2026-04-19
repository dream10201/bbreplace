package com.example.bbreplace

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioRouteController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var originalMode = AudioManager.MODE_NORMAL
    private var activeOutputRouteName = "手机扬声器"
    private var activated = false
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var activeHeadsetDevice: BluetoothDevice? = null
    private var headsetProfileLatch: CountDownLatch? = null
    private val headsetProfileListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = proxy as? BluetoothHeadset
                    headsetProfileLatch?.countDown()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = null
                    activeHeadsetDevice = null
                }
            }
        }

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
        stopHeadsetVoiceRecognition()
        closeHeadsetProfile()
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
        startHeadsetVoiceRecognition()
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
        startHeadsetVoiceRecognition()
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

    private fun startHeadsetVoiceRecognition() {
        if (!hasBluetoothConnectPermission()) {
            return
        }
        val headset = getHeadsetProfile() ?: return
        val device = headset.connectedDevices.firstOrNull() ?: return
        activeHeadsetDevice = device
        try {
            headset.stopVoiceRecognition(device)
        } catch (_: Throwable) {
        }
        try {
            headset.startVoiceRecognition(device)
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
    }

    private fun stopHeadsetVoiceRecognition() {
        if (!hasBluetoothConnectPermission()) {
            return
        }
        val headset = bluetoothHeadset ?: return
        val device = activeHeadsetDevice ?: headset.connectedDevices.firstOrNull() ?: return
        try {
            headset.stopVoiceRecognition(device)
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
        activeHeadsetDevice = null
    }

    private fun getHeadsetProfile(): BluetoothHeadset? {
        bluetoothHeadset?.let { return it }
        val adapter = bluetoothManager.adapter ?: return null
        val latch = CountDownLatch(1)
        headsetProfileLatch = latch
        val requested = adapter.getProfileProxy(appContext, headsetProfileListener, BluetoothProfile.HEADSET)
        if (!requested) {
            headsetProfileLatch = null
            return null
        }
        latch.await(1500L, TimeUnit.MILLISECONDS)
        headsetProfileLatch = null
        return bluetoothHeadset
    }

    private fun closeHeadsetProfile() {
        val adapter = bluetoothManager.adapter ?: return
        val headset = bluetoothHeadset ?: return
        adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset)
        bluetoothHeadset = null
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
