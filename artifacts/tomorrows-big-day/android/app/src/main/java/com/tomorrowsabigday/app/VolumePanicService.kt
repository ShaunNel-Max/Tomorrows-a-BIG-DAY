package com.tomorrowsabigday.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * VolumePanicService — background foreground service that detects 5 rapid
 * volume-key presses (up OR down) within 4 seconds and triggers the SOS flow.
 *
 * Detection strategy:
 *   Listens for android.media.VOLUME_CHANGED_ACTION broadcasts.
 *   This fires for EVERY physical volume button press — including presses at
 *   the min/max boundary where the volume value doesn't actually change.
 *
 * On trigger:
 *   1. Vibrates: 200ms · 100ms gap · 200ms · 100ms gap · 400ms
 *   2. Sends a PACKAGE-LOCAL broadcast so VolumePanicPlugin can bridge to JS
 *   3. If the app is in the background, also launches MainActivity with
 *      action VOLUME_PANIC so handleVolumePanicIntent() in MainActivity
 *      navigates the WebView to /panic
 */
class VolumePanicService : Service() {

    companion object {
        const val ACTION_PANIC_TRIGGERED = "com.tomorrowsabigday.app.VOLUME_PANIC_TRIGGERED"
        private const val CHANNEL_ID     = "sos_guard"
        private const val NOTIF_ID       = 9001
        private const val PRESSES_NEEDED = 5
        private const val WINDOW_MS      = 4_000L
        private const val TAG            = "VolumePanic"
    }

    private val pressTimestamps = ArrayDeque<Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val volumeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return
            // Post to main looper so pressTimestamps is always touched on one thread.
            mainHandler.post { recordPress() }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VolumePanicService.onCreate — starting foreground")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeChangedReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeChangedReceiver, filter)
        }
        Log.d(TAG, "VolumePanicService receiver registered")
    }

    override fun onDestroy() {
        Log.d(TAG, "VolumePanicService.onDestroy")
        try { unregisterReceiver(volumeChangedReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VolumePanicService.onStartCommand startId=$startId")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Press counting ──────────────────────────────────────────────────────

    private fun recordPress() {
        val now = System.currentTimeMillis()
        pressTimestamps.addLast(now)
        Log.d(TAG, "recordPress — total=${pressTimestamps.size}")

        while (pressTimestamps.isNotEmpty() && now - pressTimestamps.first() > WINDOW_MS) {
            pressTimestamps.removeFirst()
        }

        if (pressTimestamps.size >= PRESSES_NEEDED) {
            pressTimestamps.clear()
            Log.d(TAG, "recordPress — TRIGGER PANIC")
            triggerPanic()
        }
    }

    // ── Panic trigger ───────────────────────────────────────────────────────

    private fun triggerPanic() {
        vibrate()

        // Send a package-local broadcast so only our app receives it.
        // This replaces the deprecated LocalBroadcastManager.
        val intent = Intent(ACTION_PANIC_TRIGGERED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "triggerPanic — broadcast sent to package=$packageName")

        // Also launch / bring the app to the panic screen (handles lock-screen scenario).
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = "VOLUME_PANIC"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(launchIntent)
        Log.d(TAG, "triggerPanic — MainActivity launched")
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 200, 100, 200, 100, 400)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) { /* vibration not available */ }
    }

    // ── Notification ────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SOS guard active")
            .setContentText("Press volume up or down 5\u00d7 to trigger an emergency alert")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "SOS Guard",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Silent indicator that the emergency SOS trigger is active"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }
}
