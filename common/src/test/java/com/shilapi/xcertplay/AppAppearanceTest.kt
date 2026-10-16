package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAppearanceTest {
    @After fun resetRuntime() = AppAppearanceRuntime.resetForTest()

    @Test fun stableKeysRoundTripAndMissingOrUnknownValuesDefaultToAuto() {
        assertEquals(AppAppearance.AUTO, AppAppearance.fromKey(null))
        assertEquals(AppAppearance.AUTO, AppAppearance.fromKey("future-value"))
        AppAppearance.entries.forEach { assertEquals(it, AppAppearance.fromKey(it.key)) }
    }

    @Test fun explicitAppearancesIgnoreSystemTheme() {
        for (systemNight in listOf(false, true)) {
            assertTrue(resolveAppNight(AppAppearance.DARK, systemNight))
            assertFalse(resolveAppNight(AppAppearance.LIGHT, systemNight))
        }
    }

    @Test fun autoFollowsOnlySystemTheme() {
        assertTrue(resolveAppNight(AppAppearance.AUTO, true))
        assertFalse(resolveAppNight(AppAppearance.AUTO, false))
    }

    @Test fun runtimePublishesOnlyChangesAndProtectsTheCurrentOwner() {
        val firstOwner = Any()
        val secondOwner = Any()
        val observed = mutableListOf<Boolean?>()
        val stopObserving = AppAppearanceRuntime.observeHost(observed::add)

        assertEquals(listOf<Boolean?>(null), observed)
        AppAppearanceRuntime.publishHost(firstOwner, true)
        AppAppearanceRuntime.publishHost(firstOwner, true)
        AppAppearanceRuntime.publishHost(secondOwner, false)
        AppAppearanceRuntime.clearHost(firstOwner)
        assertEquals(false, AppAppearanceRuntime.hostNight())
        AppAppearanceRuntime.clearHost(secondOwner)
        assertNull(AppAppearanceRuntime.hostNight())
        assertEquals(listOf(null, true, false, null), observed)

        stopObserving()
        stopObserving()
        AppAppearanceRuntime.publishHost(firstOwner, true)
        assertEquals(listOf(null, true, false, null), observed)
    }

    @Test fun runtimeDoesNotHideObserverFailures() {
        val owner = Any()
        AppAppearanceRuntime.observeHost { night ->
            if (night != null) error("observer failed")
        }

        val failure = runCatching { AppAppearanceRuntime.publishHost(owner, true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("observer failed", failure?.message)
    }

    @Test fun appearanceRepaintWaitsForEitherWheelKeyLearningPath() {
        assertFalse(shouldDeferAppearanceRender(windowKeyLearning = false, serviceKeyLearning = false))
        assertTrue(shouldDeferAppearanceRender(windowKeyLearning = true, serviceKeyLearning = false))
        assertTrue(shouldDeferAppearanceRender(windowKeyLearning = false, serviceKeyLearning = true))
        assertTrue(shouldDeferAppearanceRender(windowKeyLearning = true, serviceKeyLearning = true))
    }
}
