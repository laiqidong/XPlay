package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shilapi.xcertplay.adb.LocalAdbDevicePolicy

/** E28 normally resumes from sleep without sending BOOT_COMPLETED. */
class P7WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_POWER_CHANGED || !LocalAdbDevicePolicy.isXpengP7()) return
        // Verified against E28 XUIService ContextInfoHalService.notifyPowerStateChanged:
        // IG 1 = driver power on (2 = remote on); power 0 = normal (1/2 = sleep).
        // Missing fields and forced shutdown must never bring an activity forward.
        if (!intent.hasExtra(EXTRA_IG) || !intent.hasExtra(EXTRA_POWER)) return
        val awake = intent.getIntExtra(EXTRA_IG, -1) == 1 &&
            intent.getIntExtra(EXTRA_POWER, -1) == 0 &&
            !intent.getBooleanExtra(EXTRA_FORCE_STOP, false)
        val prefs = context.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)
        val wasAwake = prefs.getBoolean("driver_awake", false)
        prefs.edit().putBoolean("driver_awake", awake)
            .putLong("received_at", System.currentTimeMillis()).commit()
        if (!awake || wasAwake || !AirPlayPersistence.loadAutoStartOnBoot(context)) return

        // Reuse launcher routing, including its saved automatic connection preference.
        // Reordering keeps an existing CarPlay session and returns to its main page.
        try {
            context.startActivity(ConnectionEntryPolicy.launchIntent(context))
            prefs.edit().putString("launch_result", "startActivity-returned")
                .putLong("launch_at", System.currentTimeMillis()).apply()
            Log.i(TAG, "Driver power-on: requested XPlay main page")
        } catch (error: RuntimeException) {
            // Allow a later normal-state event to retry if the system rejected this launch.
            prefs.edit().putBoolean("driver_awake", false).commit()
            prefs.edit().putString("launch_result", "failed:${error.javaClass.simpleName}")
                .putLong("launch_at", System.currentTimeMillis()).apply()
            Log.w(TAG, "Driver power-on launch failed", error)
        }
    }

    companion object {
        internal fun report(context: Context): String {
            val prefs = context.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)
            return "P7 power wake: receivedAtEpochMs=${prefs.getLong("received_at", 0)} " +
                "driverAwake=${prefs.getBoolean("driver_awake", false)} " +
                "launchAtEpochMs=${prefs.getLong("launch_at", 0)} " +
                "launchResult=${prefs.getString("launch_result", "none")}; " +
                "startActivity-returned does not prove the OEM displayed the app."
        }

        const val TAG = "XPlay-CarWake"
        const val ACTION_POWER_CHANGED = "com.xiaopeng.intent.action.XUI_POWER_STATE_CHANGED"
        const val EXTRA_IG = "android.intent.extra.IG_STATE"
        const val EXTRA_POWER = "android.intent.extra.POWER_STATE"
        const val EXTRA_FORCE_STOP = "android.intent.extra.FORCE_STOP"
    }
}
