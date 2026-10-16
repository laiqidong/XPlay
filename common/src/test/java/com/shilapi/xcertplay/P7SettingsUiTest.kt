package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w600dp-h800dp")
class P7SettingsUiTest {
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var screen: DiPlayActivity

    @Before fun setUp() {
        ShadowBuild.setManufacturer("XiaoPeng")
        ShadowBuild.setBrand("XPENG")
        ShadowBuild.setModel("E28")
        CarPlayBackgroundSession.clear()
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveWirelessEnabled(context, true)
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        screen = controller.get()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        CarPlayBackgroundSession.clear()
    }

    @Test fun overviewLinksToExactlySevenP7Categories() {
        val categories = SettingsInformationArchitecture.categories(true)
        assertEquals(7, categories.size)
        val destinationTitles = SettingsCategory.entries.map { category ->
            screen.getString(R.string.settings_open_category, title(category))
        }
        val actual = views().mapNotNull { it.settingsControlTitle }.filter { it in destinationTitles }.toList()
        val expected = (categories - SettingsCategory.OVERVIEW).map { category ->
            screen.getString(R.string.settings_open_category, title(category))
        }
        assertEquals(expected.toSet(), actual.toSet())
        assertEquals(expected.size, actual.size)
    }

    @Test fun settingsLiveWhereDriversLookForThem() {
        open(SettingsCategory.VEHICLE)
        assertPresent(R.string.wheel_siri_key, R.string.report_location_to_iphone,
            R.string.settings_gesture_fingers_hint, R.string.car_button_in_carplay, R.string.settings_p7_motion)
        assertAbsent(R.string.right_hand_drive, R.string.settings_p7_carplay_title, R.string.wheel_joystick)

        open(SettingsCategory.ADVANCED)
        assertPresent(R.string.settings_p7_carplay_title, R.string.efficient_video,
            R.string.call_echo_cancellation, R.string.settings_diagnostic_tools)
        assertChoice(R.string.mfi_certificate_signing_target, true)
        assertAbsent(R.string.report_location_to_iphone, R.string.settings_vehicle_probe_title,
            R.string.carplay_map_on_instrument_cluster_experimental, R.string.settings_ambient_title,
            R.string.settings_harman_fullscreen_experimental, R.string.settings_p7_motion, R.string.settings_p7_location_link)

        open(SettingsCategory.CONNECTION)
        assertPresent(R.string.connect_when_iphone_bluetooth_connects, R.string.connect_when_diplay_opens)
        assertChoice(R.string.mfi_certificate_signing_target, false)
        assertChoice(R.string.settings_usb_charging, false)
        assertAbsent(R.string.usb_auto_confirm_title)

        open(SettingsCategory.AUDIO)
        assertChoice(R.string.music_buffer, true)
        assertAbsent(R.string.efficient_video)

        open(SettingsCategory.ABOUT)
        assertChoice(R.string.language_app_language, true)
        assertPresent(R.string.backup_open)
    }

    @Test fun switchingToUsbRevealsChargingAndPermissionControls() {
        AirPlayPersistence.saveWirelessEnabled(screen, false)
        open(SettingsCategory.CONNECTION)
        assertChoice(R.string.settings_usb_charging, true)
        assertPresent(R.string.usb_auto_confirm_title)
        AirPlayPersistence.saveWirelessEnabled(screen, true)
        open(SettingsCategory.CONNECTION)
        assertChoice(R.string.settings_usb_charging, false)
        assertAbsent(R.string.usb_auto_confirm_title)
    }

    @Test fun diagnosticsOpenBelowAdvancedAndBackReturnsToAdvanced() {
        open(SettingsCategory.ADVANCED)
        views().filterIsInstance<TextView>().single { it.text == screen.getString(R.string.settings_diagnostic_tools) }.performClick()
        assertEquals(SettingsCategory.DIAGNOSTICS, category())
        assertPresent(R.string.save_diagnostic_report, R.string.settings_vehicle_probe_title)
        assertAbsent(R.string.efficient_video)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "navigateBack")
        assertEquals(SettingsCategory.ADVANCED, category())
        assertPresent(R.string.settings_diagnostic_tools)
    }

    @Test fun movedCategoriesAndVehicleLocationRemainReachable() {
        open(SettingsCategory.LANGUAGE)
        assertEquals(SettingsCategory.ABOUT, category())
        assertChoice(R.string.language_app_language, true)
        open(SettingsCategory.EXPERIMENTS)
        assertEquals(SettingsCategory.ADVANCED, category())
        open(SettingsCategory.NAVIGATION)
        assertEquals(SettingsCategory.VEHICLE, category())
        assertPresent(R.string.report_location_to_iphone, R.string.settings_p7_motion)
    }

    @Test fun menuChangesPreserveSavedConnectionAndAudioPreferences() {
        AirPlayPersistence.saveMfiTarget(screen, MfiTarget.USB_CH341)
        AirPlayPersistence.saveMediaBufferMillis(screen, 500)
        AirPlayPersistence.saveRightHandDrive(screen, true)
        SettingsInformationArchitecture.categories(true).forEach(::open)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(screen))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(screen))
        assertFalse(AirPlayPersistence.loadRightHandDrive(screen))
    }

    private fun open(category: SettingsCategory) = ReflectionHelpers.callInstanceMethod<Unit>(screen,
        "openSettingsCategory", ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
    private fun category(): SettingsCategory = ReflectionHelpers.getField(screen, "settingsCategory")
    private fun title(category: SettingsCategory): String = ReflectionHelpers.callInstanceMethod(screen,
        "settingsCategoryTitle", ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
    private fun views(): Sequence<View> = descendants(screen.window.decorView)
    private fun assertPresent(vararg ids: Int) = ids.forEach { id ->
        assertTrue(screen.getString(id), views().any { it.settingsControlTitle == screen.getString(id) ||
            it is TextView && it.text == screen.getString(id) })
    }
    private fun assertAbsent(vararg ids: Int) = ids.forEach { id ->
        assertFalse(screen.getString(id), views().any { it.settingsControlTitle == screen.getString(id) ||
            it is TextView && it.text == screen.getString(id) })
    }
    private fun assertChoice(id: Int, expected: Boolean) = assertEquals(screen.getString(id), expected,
        views().filterIsInstance<TextView>().any { it.text.startsWith(screen.getString(id) + " · ") })
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
