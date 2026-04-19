package com.example.bbreplace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val store = RunStateStore(context)
        if (!store.shouldKeepRunning()) {
            return
        }

        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            SpeechRepeaterService.ACTION_RESTART -> {
                SpeechRepeaterService.start(context)
            }
        }
    }
}
