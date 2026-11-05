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
        if (!LocalAdbDevicePolicy.isXpengP7()) return
        if (intent.action == Intent.ACTION_SCREEN_OFF) {
            driverAwake = false
            P7WakeForeground.cancel()
            CarWakePrewarm.stop()
            context.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE).edit()
                .putBoolean("driver_awake", false)
                .putLong("screen_off_at", System.currentTimeMillis()).apply()
            return
        }
        if (intent.action != ACTION_POWER_CHANGED) return
        install(context)
        // Missing fields must never bring an activity forward.
        if (!intent.hasExtra(EXTRA_IG) || !intent.hasExtra(EXTRA_POWER)) return
        val ig = intent.getIntExtra(EXTRA_IG, -1)
        val power = intent.getIntExtra(EXTRA_POWER, -1)
        val state = P7PowerState.of(ig, power, intent.getBooleanExtra(EXTRA_FORCE_STOP, false))
        val prefs = context.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("driver_awake", state == P7PowerState.DRIVER_AWAKE)
            .putString("last_state", "ig=$ig power=$power $state")
            .putLong("received_at", System.currentTimeMillis()).commit()
        when (state) {
            // IG -1 arrives around every wake and sleep without saying who is in the car.
            P7PowerState.UNKNOWN -> return
            P7PowerState.ASLEEP -> {
                P7WakeForeground.cancel()
                if (driverAwake) CarWakePrewarm.stop()
                driverAwake = false
                return
            }
            P7PowerState.DRIVER_AWAKE -> {
                // In memory on purpose: the E28 force-stops DiPlay at IG off, often before the IG 0
                // broadcast reaches it, so a saved flag would skip the next drive's wake.
                val suspended = suspendClockAdvanced()
                if (driverAwake && !suspended) {
                    Log.i(TAG, "Duplicate driver power-on ignored")
                    return
                }
                driverAwake = true
            }
        }
        if (!AirPlayPersistence.loadAutoStartOnBoot(context)) return
        CarWakePrewarm.start(context)

        // Hold the broadcast only for the first attempts; later retries must not stall the car's queue.
        val pending = goAsync()
        P7WakeForeground.launch(context.applicationContext) { pending.finish() }
    }

    companion object {
        @Volatile private var driverAwake = false
        private var wakeWatcher: BroadcastReceiver? = null
        private var lastSuspendClock = 0L
        private const val SUSPEND_REARM_MS = 5_000L

        private fun suspendClockAdvanced(): Boolean {
            // elapsedRealtime includes deep sleep; uptimeMillis does not. Retained E28 processes
            // can miss both IG off and SCREEN_OFF, so a real suspend must also re-arm ignition.
            val clock = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            val suspended = clock - lastSuspendClock >= SUSPEND_REARM_MS
            lastSuspendClock = clock
            return suspended
        }

        internal fun install(context: Context) {
            if (!LocalAdbDevicePolicy.isXpengP7() || wakeWatcher != null) return
            lastSuspendClock = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
            val receiver = P7WakeReceiver()
            val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(ACTION_POWER_CHANGED)
            }
            // Some XUI power events target registered receivers only. Install as soon as the app
            // opens, not after its first manifest broadcast; keep it for the process lifetime.
            val app = context.applicationContext
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            wakeWatcher = receiver
        }

        internal fun report(context: Context): String {
            val prefs = context.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)
            return "P7 power wake: receivedAtEpochMs=${prefs.getLong("received_at", 0)} " +
                "lastState=${prefs.getString("last_state", "none")} " +
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

/**
 * Verified against E28 XUIService ContextInfoHalService.notifyPowerStateChanged, which always sends both
 * extras: IG 1 = driver power on, 2 = remote on, 0 = off, -1 = not known yet; power 0 = normal, 1/2 = sleep.
 */
internal enum class P7PowerState {
    DRIVER_AWAKE, ASLEEP, UNKNOWN;

    companion object {
        fun of(ig: Int, power: Int, forceStop: Boolean): P7PowerState = when {
            forceStop || ig == 0 || ig == 2 || power == 1 || power == 2 -> ASLEEP
            ig == 1 && power == 0 -> DRIVER_AWAKE
            else -> UNKNOWN
        }
    }
}

/** A wake request succeeds only when a DiPlay window actually receives focus. */
internal object P7WakeForeground {
    /** The broadcast is held this long; [WINDOW_MS] keeps going after it is released. */
    private const val HOLD_BROADCAST_MS = 8_000L
    /** The parking view (reverse gear) blocks third-party launches; leaving a space can take this long. */
    private const val WINDOW_MS = 60_000L
    private const val RETRY_MS = 3_000L

    /**
     * The OEM launcher may take the foreground while restoring the car after sleep, and while
     * XPeng's parking scenario runs every start is dropped (XAppCheckPolicy ret 7) and never retried.
     * Stop after stable focus, never retry throughout an established drive.
     */
    internal val ATTEMPT_DELAYS_MS: List<Long> =
        listOf(0L, 1_500L, 4_000L, 7_000L) + (10_000L..WINDOW_MS - RETRY_MS step RETRY_MS)

    private val handler = Handler(Looper.getMainLooper())
    private var request = 0L
    private var finish: (() -> Unit)? = null
    private var app: Context? = null
    private var windowFocused = false
    private var focusGeneration = 0L
    private const val STABLE_FOCUS_MS = 5_000L

    fun launch(context: Context, onFinished: () -> Unit) {
        cancel()
        app = context
        finish = onFinished
        windowFocused = false
        request = SystemClock.elapsedRealtimeNanos()
        val id = request
        val prefs = context.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)
        prefs.edit().putString("foreground_result", "waiting-for-focus")
            .putInt("foreground_attempts", 0).apply()
        ATTEMPT_DELAYS_MS.forEachIndexed { index, delay ->
            handler.postDelayed({
                if (request != id) return@postDelayed
                if (!AirPlayPersistence.loadAutoStartOnBoot(context)) {
                    cancel()
                    return@postDelayed
                }
                if (windowFocused) return@postDelayed
                prefs.edit().putInt("foreground_attempts", index + 1)
                    .putLong("launch_at", System.currentTimeMillis()).apply()
                try {
                    context.startActivity(ConnectionEntryPolicy.launchIntent(context))
                    prefs.edit().putString("launch_result", "startActivity-returned").apply()
                } catch (error: RuntimeException) {
                    prefs.edit().putString("launch_result", "failed:${error.javaClass.simpleName}").apply()
                    Log.w(P7WakeReceiver.TAG, "Driver power-on launch failed", error)
                }
            }, delay)
        }
        handler.postDelayed({ if (request == id) releaseBroadcast() }, HOLD_BROADCAST_MS)
        handler.postDelayed({
            if (request == id) {
                prefs.edit().putString("foreground_result", "focus-not-confirmed").apply()
                complete()
            }
        }, WINDOW_MS)
    }

    /** OEM restoration may cover a retained Activity immediately after its first focus. */
    fun focused() = focusChanged(true)

    fun focusChanged(hasFocus: Boolean) {
        windowFocused = hasFocus
        val generation = ++focusGeneration
        if (request == 0L || !hasFocus) return
        val id = request
        handler.postDelayed({
            if (request != id || focusGeneration != generation || !windowFocused) return@postDelayed
            app?.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)?.edit()
                ?.putString("foreground_result", "window-focused")?.apply()
            complete()
        }, STABLE_FOCUS_MS)
    }

    fun cancel() {
        if (request != 0L) app?.getSharedPreferences("xplay_car_wake", Context.MODE_PRIVATE)
            ?.edit()?.putString("foreground_result", "cancelled")?.apply()
        complete()
    }

    private fun releaseBroadcast() {
        val callback = finish
        finish = null
        callback?.invoke()
    }

    private fun complete() {
        request = 0L
        handler.removeCallbacksAndMessages(null)
        app = null
        releaseBroadcast()
    }
}
