package com.example.bbreplace

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class SpeechRepeaterService : Service() {
    private val engine by lazy { SpeechRepeaterEngine(this, StatusReporter::publish) }
    private val runStateStore by lazy { RunStateStore(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runStateStore.setShouldKeepRunning(false)
                engine.stop()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }

            ACTION_START, null -> {
                runStateStore.setShouldKeepRunning(true)
                startForeground(NOTIFICATION_ID, buildNotification())
                engine.start()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (runStateStore.shouldKeepRunning()) {
            scheduleRestart()
        }
    }

    override fun onDestroy() {
        engine.stop()
        if (runStateStore.shouldKeepRunning()) {
            scheduleRestart()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(createLaunchPendingIntent())
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.service_notification_stop_action),
                createStopPendingIntent(),
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .build()

    private fun createLaunchPendingIntent(): PendingIntent {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                ?: Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
        return PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createStopPendingIntent(): PendingIntent {
        val stopIntent =
            Intent(this, SpeechRepeaterService::class.java).apply {
                action = ACTION_STOP
            }
        return PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleRestart() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val restartIntent =
            Intent(this, AutoRestartReceiver::class.java).apply {
                action = ACTION_RESTART
                component = ComponentName(this@SpeechRepeaterService, AutoRestartReceiver::class.java)
            }
        val pendingIntent =
            PendingIntent.getBroadcast(
                this,
                RESTART_REQUEST_CODE,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val triggerAtMillis = System.currentTimeMillis() + RESTART_DELAY_MS
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent,
        )
    }

    companion object {
        private const val CHANNEL_ID = "speech_repeater_channel"
        private const val NOTIFICATION_ID = 1001
        private const val OPEN_APP_REQUEST_CODE = 2001
        private const val RESTART_REQUEST_CODE = 2002
        private const val STOP_REQUEST_CODE = 2003
        private const val RESTART_DELAY_MS = 1500L
        const val ACTION_START = "com.example.bbreplace.action.START"
        const val ACTION_STOP = "com.example.bbreplace.action.STOP"
        const val ACTION_RESTART = "com.example.bbreplace.action.RESTART_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, SpeechRepeaterService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SpeechRepeaterService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
