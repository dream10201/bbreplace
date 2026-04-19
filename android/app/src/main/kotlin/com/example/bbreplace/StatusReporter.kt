package com.example.bbreplace

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object StatusReporter {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentStatus: Map<String, Any> = mapOf(
        "state" to "idle",
        "message" to "未启动",
        "isRunning" to false,
        "lastUtteranceMs" to 0,
        "inputRoute" to "未知",
        "outputRoute" to "未知",
    )

    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    fun publish(status: Map<String, Any>) {
        currentStatus = status
        mainHandler.post {
            eventSink?.success(status)
        }
    }

    fun snapshot(): Map<String, Any> = currentStatus

    val streamHandler = object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
            eventSink = events
            events.success(currentStatus)
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }
    }
}
