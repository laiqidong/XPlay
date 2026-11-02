package com.shilapi.xcertplay

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class P7WakeReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val looper get() = shadowOf(Looper.getMainLooper())

    @Before fun enableAutoStart() { AirPlayPersistence.saveAutoStartOnBoot(app, true) }
    @After fun cleanup() { P7WakeForeground.cancel(); CarPlayBackgroundSession.clear() }

    @Test fun onlyDriverPowerOnIsAWake() {
        assertEquals(P7PowerState.DRIVER_AWAKE, P7PowerState.of(ig = 1, power = 0, forceStop = false))
        assertEquals(P7PowerState.ASLEEP, P7PowerState.of(ig = 1, power = 0, forceStop = true))
        assertEquals(P7PowerState.ASLEEP, P7PowerState.of(ig = 0, power = 0, forceStop = false))
        assertEquals(P7PowerState.ASLEEP, P7PowerState.of(ig = 2, power = 0, forceStop = false))
        assertEquals(P7PowerState.ASLEEP, P7PowerState.of(ig = -1, power = 2, forceStop = false))
    }

    @Test fun unknownIgnitionNeitherWakesNorCancels() {
        // Seen on the E28 right before and after IG 1 on every wake.
        assertEquals(P7PowerState.UNKNOWN, P7PowerState.of(ig = -1, power = 0, forceStop = false))
    }

    @Test fun retriesOutlastTheParkingViewButStayBounded() {
        val delays = P7WakeForeground.ATTEMPT_DELAYS_MS
        assertEquals(listOf(0L, 1_500L, 4_000L, 7_000L), delays.take(4))
        // A reverse out of a parking space kept launches blocked for 18 s in the field logs.
        assertTrue(delays.count { it > 30_000L } >= 5)
        assertTrue(delays.last() < 60_000L)
        assertEquals(delays.sorted(), delays)
    }

    @Test fun keepsRetryingAfterTheBroadcastIsReleased() {
        var released = 0
        P7WakeForeground.launch(app) { released++ }
        looper.idleFor(Duration.ofSeconds(9))
        assertEquals(1, released)
        val early = startedCount()
        looper.idleFor(Duration.ofSeconds(20))
        assertTrue("launches must continue after the broadcast is released", startedCount() > 0)
        assertEquals(4, early)
    }

    @Test fun anyDiPlayFocusStopsTheRetries() {
        var released = 0
        P7WakeForeground.launch(app) { released++ }
        looper.idleFor(Duration.ofSeconds(2))
        startedCount()
        P7WakeForeground.focused()
        assertEquals(1, released)
        looper.idleFor(Duration.ofSeconds(60))
        assertEquals(0, startedCount())
        assertEquals(1, released)
    }

    @Test fun windowEndsWithoutFocus() {
        P7WakeForeground.launch(app) {}
        looper.idleFor(Duration.ofSeconds(61))
        startedCount()
        looper.idleFor(Duration.ofSeconds(30))
        assertEquals(0, startedCount())
        assertEquals("focus-not-confirmed", app.getSharedPreferences("xplay_car_wake", 0)
            .getString("foreground_result", null))
    }

    private fun startedCount(): Int {
        val shadow = shadowOf(app)
        var count = 0
        while (shadow.nextStartedActivity != null) count++
        return count
    }
}
