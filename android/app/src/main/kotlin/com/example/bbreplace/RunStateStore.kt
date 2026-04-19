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

    fun setInputMode(inputMode: String) {
        preferences.edit().putString(KEY_INPUT_MODE, inputMode).apply()
    }

    fun getInputMode(): String =
        preferences.getString(KEY_INPUT_MODE, INPUT_MODE_AUTO) ?: INPUT_MODE_AUTO

    fun setVadMode(vadMode: Int) {
        preferences.edit().putInt(KEY_VAD_MODE, vadMode).apply()
    }

    fun getVadMode(): Int =
        preferences.getInt(KEY_VAD_MODE, DEFAULT_VAD_MODE)

    companion object {
        private const val PREFS_NAME = "speech_repeater_prefs"
        private const val KEY_SHOULD_KEEP_RUNNING = "should_keep_running"
        private const val KEY_INPUT_MODE = "input_mode"
        private const val KEY_VAD_MODE = "vad_mode"

        const val INPUT_MODE_AUTO = "auto"
        const val INPUT_MODE_BLUETOOTH = "bluetooth"
        const val INPUT_MODE_PHONE = "phone"
        const val DEFAULT_VAD_MODE = 2
    }
}
