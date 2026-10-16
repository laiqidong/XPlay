package com.shilapi.xcertplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.view.Surface
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.mockito.Mockito.mock
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AndroidMediaSink

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LocationReportingSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private val activity get() = requireNotNull(controller).get()

    @Before fun setUp() {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        controller?.pause()?.stop()?.destroy()
    }

    @Test fun driftProtectionDefaultsOffPersistsAndDoesNotInterruptTheSession() {
        openSettings()
        assertFalse(AirPlayPersistence.loadLocationDriftProtection(context))
        assertFalse(driftSwitch().isChecked)
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        driftSwitch().performClick()
        assertTrue(AirPlayPersistence.loadLocationDriftProtection(context))
        assertTrue(PendingReconnect.isPending(session))
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
        assertEquals(0, stops)
        assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        requireNotNull(controller).recreate()
        assertTrue(driftSwitch().isChecked)
        driftSwitch().performClick()
        assertFalse(AirPlayPersistence.loadLocationDriftProtection(context))
        assertEquals(0, stops)
    }

    @Test fun currentSettingsExposeLocationSwitchWithDefaultOff() {
        openSettings()

        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun grantedSettingSurvivesRecreationAndCanBeDisabled() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        openSettings()

        locationSwitch().performClick()
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
        requireNotNull(controller).recreate()
        assertTrue(locationSwitch().isChecked)

        locationSwitch().performClick()
        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun enablingWaitsForPrecisePermissionAndRejectsApproximateOnly() {
        openSettings()
        locationSwitch().performClick()

        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            request.requestedPermissions)

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED))
        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun precisePermissionResultEnablesReporting() {
        openSettings()
        locationSwitch().performClick()
        val request = shadowOf(activity).lastRequestedPermission

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED))

        assertTrue(locationSwitch().isChecked)
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun eitherToggleDirectionStopsTheOldSessionAndOpensANewHostWithSavedSetting() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        openSettings()
        // Authentication assets are deliberately absent from unit tests. Model an already
        // provisioned, connected host without constructing transports or native decoders.
        ReflectionHelpers.setField(activity, "setupError", null)
        val stoppedWithSettings = mutableListOf<Boolean>()

        for (enabled in listOf(true, false)) {
            val stop: ((() -> Unit) -> Unit) = { completion ->
                stoppedWithSettings += AirPlayPersistence.loadLocationReportingEnabled(context)
                CarPlayBackgroundSession.clear()
                completion()
            }
            ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)

            locationSwitch().performClick()

            assertEquals(enabled, AirPlayPersistence.loadLocationReportingEnabled(context))
            assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        }
        assertEquals(listOf(true, false), stoppedWithSettings)
    }

    private fun openSettings() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val target = activity.getString(R.string.settings_open_category, activity.getString(R.string.settings_navigation))
        descendants(activity.window.decorView).first { it.settingsControlTitle == target }.performClick()
    }

    private fun locationSwitch(): Switch = descendants(activity.window.decorView)
        .filterIsInstance<Switch>()
        .single { it.settingsControlTitle == activity.getString(R.string.report_location_to_iphone) }

    private fun driftSwitch(): Switch = descendants(activity.window.decorView)
        .filterIsInstance<Switch>()
        .single { it.settingsControlTitle == activity.getString(R.string.settings_location_drift_protection) }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
