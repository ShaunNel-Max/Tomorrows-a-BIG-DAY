package com.tomorrowsabigday.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * VolumePanicPlugin — Capacitor bridge for the volume-key SOS trigger.
 *
 * Starts a foreground service (VolumePanicService) that monitors rapid
 * volume button presses in the background and on the lock screen.
 * When 5 presses are detected within 4 seconds, the service:
 *   1. Vibrates the phone (confirmation feedback)
 *   2. Sends a package-local broadcast that this plugin catches
 *   3. This plugin fires the Capacitor JS event so the React router
 *      navigates to /panic and begins recording automatically.
 *
 * REGISTRATION: Already done in MainActivity.java — no extra setup needed.
 *
 * The deprecated androidx.localbroadcastmanager.content.LocalBroadcastManager
 * has been replaced by a standard BroadcastReceiver with a package-scoped
 * intent (setPackage).  This is the Google-recommended migration and is
 * more reliable across OEM ROMs.
 */
@CapacitorPlugin(name = "VolumePanic")
class VolumePanicPlugin : Plugin() {

    private val panicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VolumePanicService.ACTION_PANIC_TRIGGERED) return
            Log.d("VolumePanicPlugin", "panicReceiver — firing JS event")
            notifyListeners("volumePanicTriggered", com.getcapacitor.JSObject())
        }
    }

    override fun load() {
        Log.d("VolumePanicPlugin", "load() — registering receiver")
        val filter = IntentFilter(VolumePanicService.ACTION_PANIC_TRIGGERED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // RECEIVER_NOT_EXPORTED = package-local only (same security model as LocalBroadcastManager)
            context.registerReceiver(panicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(panicReceiver, filter)
        }
        startService()
    }

    override fun handleOnDestroy() {
        Log.d("VolumePanicPlugin", "handleOnDestroy() — unregistering receiver")
        try { context.unregisterReceiver(panicReceiver) } catch (_: Exception) {}
    }

    @PluginMethod
    fun startMonitoring(call: PluginCall?) {
        Log.d("VolumePanicPlugin", "startMonitoring() — called from JS")
        startService()
        call?.resolve()
    }

    @PluginMethod
    fun stopMonitoring(call: PluginCall?) {
        Log.d("VolumePanicPlugin", "stopMonitoring() — stopping service")
        context.stopService(Intent(context, VolumePanicService::class.java))
        call?.resolve()
    }

    private fun startService() {
        val intent = Intent(context, VolumePanicService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d("VolumePanicPlugin", "startService() — service started OK")
        } catch (e: Exception) {
            Log.e("VolumePanicPlugin", "startService() — FAILED: ${e.message}", e)
        }
    }
}
