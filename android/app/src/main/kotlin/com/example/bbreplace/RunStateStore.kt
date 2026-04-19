package com.example.bbreplace

import android.content.Context

class RunStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setShouldKeepRunning(shouldRun: Boolean) {
        preferences.edit().putBoolean(KEY_SHOULD_KEEP_RUNNING, shouldRun).apply()
    }

    fun shouldKeepRunning(): Boolean =
        preferences.getBoolean(KEY_SHOULD_KEEP_RUNNING, false)

    companion object {
        private const val PREFS_NAME = "speech_repeater_prefs"
        private const val KEY_SHOULD_KEEP_RUNNING = "should_keep_running"
    }
}
