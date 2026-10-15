package com.shilapi.xcertplay

import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class SessionTaskRemovalTest {
    @After fun cleanup() { CarPlayBackgroundSession.clear() }

    @Test fun removingUiTaskKeepsConnectionUntilExplicitDisconnect() {
        var stops = 0
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        CarPlayBackgroundSession.active = true
        val service = Robolectric.buildService(DiPlaySessionService::class.java).create().get()
        service.onTaskRemoved(Intent())
        assertEquals(0, stops)
        assertTrue(CarPlayBackgroundSession.active)
        assertFalse(shadowOf(service).isStoppedBySelf)
        service.onStartCommand(Intent().setAction(DiPlaySessionService.ACTION_STOP), 0, 1)
        assertEquals(1, stops)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
