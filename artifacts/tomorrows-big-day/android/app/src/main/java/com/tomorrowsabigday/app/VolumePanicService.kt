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
 * VolumePanicService — background foreground service that detects the EXACT
 * 5-press SOS sequence ↑↑↓↓↑ (Up Up Down Down Up) within 2 seconds.
 *
 * Detection strategy:
 *   Listens for android.media.VOLUME_CHANGED_ACTION broadcasts.
 *   This fires for EVERY physical volume button press — including presses at
 *   the min/max boundary where the volume value doesn't actually change.
 *
 *   EXACT SEQUENCE MATCH (primary false-positive guard):
 *   The last 5 recorded press directions must be exactly [↑, ↑, ↓, ↓, ↑]
 *   i.e. [1, 1, -1, -1, 1].  Any other combination — including normal
 *   volume adjustment (all UP or all DOWN) — does NOT trigger:
 *     • ↑↑↑↑↑  → NO trigger ✅
 *     • ↓↓↓↓↓  → NO trigger ✅
 *     • ↑↓↑↓↑  → NO trigger ✅  (old alternating pattern — also rejected)
 *     • ↑↑↓↓↑  → TRIGGER     ✅  (the intentional panic gesture)
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
        // All 5 presses must land within this rolling window.
        private const val WINDOW_MS      = 2_000L
        // Minimum gap between two counted presses — filters out hold-button
        // auto-repeat (~150 ms) while accepting genuine rapid taps (~300 ms).
        private const val MIN_GAP_MS     = 250L
        // The exact direction sequence that triggers SOS: ↑↑↓↓↑
        // 1 = volume-up, -1 = volume-down.
        private val SOS_SEQUENCE         = listOf(1, 1, -1, -1, 1)
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

        // Check whether the last 5 presses match the exact ↑↑↓↓↑ sequence.
        // takeLast() handles the case where the deque has grown beyond 5 entries
        // (presses accumulate until the oldest falls outside WINDOW_MS).
        val lastFive = pressDirections.toList().takeLast(PRESSES_NEEDED)
        Log.d(TAG, "recordPress — presses=${pressTimestamps.size} lastFive=$lastFive")

        if (lastFive == SOS_SEQUENCE) {
            pressTimestamps.clear()
            pressDirections.clear()
            lastPressTime = 0L
            Log.d(TAG, "recordPress — TRIGGER PANIC ↑↑↓↓↑ matched")
            triggerPanic()
        }
        // Sequence not matched yet — let the window slide and keep accumulating.
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
            .setContentText("SOS: press Up Up Down Down Up (5 presses) to trigger emergency alert")
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
