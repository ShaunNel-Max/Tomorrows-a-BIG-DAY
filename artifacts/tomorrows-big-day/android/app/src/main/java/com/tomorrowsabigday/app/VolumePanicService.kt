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
 * volume-key presses within 2 seconds AND at least 2 direction changes
 * (e.g. UP → DOWN → UP) before triggering the SOS flow.
 *
 * Detection strategy:
 *   Listens for android.media.VOLUME_CHANGED_ACTION broadcasts.
 *   This fires for EVERY physical volume button press — including presses at
 *   the min/max boundary where the volume value doesn't actually change.
 *
 *   IMPORTANT — direction-change guard (primary false-positive fix):
 *   Normal volume adjustment is 5+ presses ALL in the same direction.
 *   We require at least 2 direction reversals (UP→DOWN counts as 1,
 *   DOWN→UP counts as another) before triggering. This means:
 *     • UP×5              → 0 changes → NO trigger ✅
 *     • DOWN×5            → 0 changes → NO trigger ✅
 *     • UP×3 + DOWN×2     → 1 change  → NO trigger ✅
 *     • UP×2+DOWN×1+UP×2  → 2 changes → TRIGGER    ✅
 *   The deliberate panic gesture is: tap up, tap down, tap up (≥5 total).
 *
 *   IMPORTANT — hold-button false-positive prevention:
 *   Android fires VOLUME_CHANGED_ACTION repeatedly while the button is held
 *   (auto-repeat at ~150 ms intervals). MIN_GAP_MS = 250 ms filters these out.
 *   Genuine rapid deliberate taps (one tap ≈ 300 ms apart) pass through.
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
        // 5 deliberate rapid taps must land within 2 s.
        private const val WINDOW_MS      = 2_000L
        // Minimum gap between two counted presses — filters out hold-button
        // auto-repeat (~150 ms) while accepting genuine rapid taps (~300 ms).
        private const val MIN_GAP_MS     = 250L
        // Minimum direction changes required in the window.
        // Prevents normal volume adjustment (all UP or all DOWN) from triggering.
        private const val MIN_DIR_CHANGES = 2
        private const val TAG            = "VolumePanic"
    }

    private val pressTimestamps  = ArrayDeque<Long>()
    private val pressDirections  = ArrayDeque<Int>()   // 1 = up, -1 = down
    private var lastPressTime    = 0L
    private var lastKnownDir     = 0   // carried forward for boundary presses
    private val mainHandler = Handler(Looper.getMainLooper())

    private val volumeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return
            val newVol  = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            // Determine direction; boundary presses (at min/max) carry the last known dir.
            val rawDir = when {
                newVol > prevVol -> 1
                newVol < prevVol -> -1
                else             -> 0   // at boundary — will use lastKnownDir
            }
            mainHandler.post { recordPress(rawDir) }
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

    private fun recordPress(rawDir: Int) {
        val now = System.currentTimeMillis()

        // Drop hold-button auto-repeat events.
        if (now - lastPressTime < MIN_GAP_MS) return
        lastPressTime = now

        // Resolve effective direction — boundary press inherits last known direction.
        val effectiveDir = if (rawDir != 0) { lastKnownDir = rawDir; rawDir } else lastKnownDir
        if (effectiveDir == 0) return   // no direction info yet (very first press), skip

        pressTimestamps.addLast(now)
        pressDirections.addLast(effectiveDir)
        Log.d(TAG, "recordPress dir=$effectiveDir — counted=${pressTimestamps.size}")

        // Evict presses that fell outside the rolling window.
        while (pressTimestamps.isNotEmpty() && now - pressTimestamps.first() > WINDOW_MS) {
            pressTimestamps.removeFirst()
            pressDirections.removeFirst()
        }

        if (pressTimestamps.size < PRESSES_NEEDED) return

        // Count direction reversals in the window.
        var dirChanges = 0
        var prev = pressDirections.first()
        for (d in pressDirections.drop(1)) {
            if (d != prev) { dirChanges++; prev = d }
        }

        Log.d(TAG, "recordPress — presses=${pressTimestamps.size} dirChanges=$dirChanges")

        if (dirChanges >= MIN_DIR_CHANGES) {
            pressTimestamps.clear()
            pressDirections.clear()
            lastPressTime = 0L
            Log.d(TAG, "recordPress — TRIGGER PANIC")
            triggerPanic()
        }
        // If not enough direction changes, let the window slide — don't clear.
    }

    // ── Panic trigger ───────────────────────────────────────────────────────

    private fun triggerPanic() {
        vibrate()

        // Send a package-local broadcast so only our app receives it.
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
            .setContentText("Press volume up + down rapidly 5\u00d7 to trigger an emergency alert")
            .setSmallIcon(R.drawable.ic_sos_shield)
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
