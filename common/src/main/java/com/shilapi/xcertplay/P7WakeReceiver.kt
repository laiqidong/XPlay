package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        if (!awake) {
            P7WakeForeground.cancel()
            if (wasAwake) CarWakePrewarm.stop()
        }
        if (!awake || wasAwake || !AirPlayPersistence.loadAutoStartOnBoot(context)) return
        CarWakePrewarm.start(context)

        // Keep the receiver alive only for a bounded foreground confirmation window.
        val pending = goAsync()
        P7WakeForeground.launch(context.applicationContext) { pending.finish() }
    }

    companion object {
        internal fun report(context: Context): String {
            val prefs = context.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)
            return "P7 power wake: receivedAtEpochMs=${prefs.getLong("received_at", 0)} " +
                "driverAwake=${prefs.getBoolean("driver_awake", false)} " +
                "launchAtEpochMs=${prefs.getLong("launch_at", 0)} " +
                "launchResult=${prefs.getString("launch_result", "none")}; " +
                "foregroundResult=${prefs.getString("foreground_result", "none")} " +
                "attempts=${prefs.getInt("foreground_attempts", 0)}"
        }

        const val TAG = "XPlay-CarWake"
        const val ACTION_POWER_CHANGED = "com.xiaopeng.intent.action.XUI_POWER_STATE_CHANGED"
        const val EXTRA_IG = "android.intent.extra.IG_STATE"
        const val EXTRA_POWER = "android.intent.extra.POWER_STATE"
        const val EXTRA_FORCE_STOP = "android.intent.extra.FORCE_STOP"
    }
}

/** A wake request succeeds only when its target window actually receives focus. */
internal object P7WakeForeground {
    private const val EXTRA_REQUEST = "xplay_wake_foreground_request"
    private val handler = Handler(Looper.getMainLooper())
    private var request = 0L
    private var finish: (() -> Unit)? = null
    private var app: Context? = null

    fun launch(context: Context, onFinished: () -> Unit) {
        cancel()
        app = context
        finish = onFinished
        request = SystemClock.elapsedRealtimeNanos()
        val id = request
        val prefs = context.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)
        prefs.edit().putString("foreground_result", "waiting-for-focus")
            .putInt("foreground_attempts", 0).apply()
        // The OEM launcher may take the foreground while restoring the car after sleep.
        // Stop immediately after focus, never retry throughout an established drive.
        listOf(0L, 1_500L, 4_000L, 7_000L).forEachIndexed { index, delay ->
            handler.postDelayed({
                if (request != id) return@postDelayed
                if (!AirPlayPersistence.loadAutoStartOnBoot(context)) {
                    cancel()
                    return@postDelayed
                }
                prefs.edit().putInt("foreground_attempts", index + 1)
                    .putLong("launch_at", System.currentTimeMillis()).apply()
                try {
                    context.startActivity(ConnectionEntryPolicy.launchIntent(context)
                        .putExtra(EXTRA_REQUEST, id))
                    prefs.edit().putString("launch_result", "startActivity-returned").apply()
                } catch (error: RuntimeException) {
                    prefs.edit().putString("launch_result", "failed:${error.javaClass.simpleName}").apply()
                    Log.w(P7WakeReceiver.TAG, "Driver power-on launch failed", error)
                }
            }, delay)
        }
        handler.postDelayed({
            if (request == id) {
                prefs.edit().putString("foreground_result", "focus-not-confirmed").apply()
                complete()
            }
        }, 8_000L)
    }

    fun focused(intent: Intent) {
        if (request == 0L || intent.getLongExtra(EXTRA_REQUEST, 0) != request) return
        app?.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)?.edit()
            ?.putString("foreground_result", "window-focused")?.apply()
        complete()
    }

    fun cancel() {
        if (request != 0L) app?.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)
            ?.edit()?.putString("foreground_result", "cancelled")?.apply()
        complete()
    }

    private fun complete() {
        request = 0L
        handler.removeCallbacksAndMessages(null)
        app = null
        val callback = finish
        finish = null
        callback?.invoke()
    }
}
