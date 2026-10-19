package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class AdaptiveSettingsUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @Test
    @Config(sdk = [29], qualifiers = "ja-w320dp-h600dp")
    fun japaneseSettingsDoNotEllipsizeVisibleTextOnNarrowScreens() {
        verifySettingsDoNotEllipsizeVisibleText("アプリの言語")
    }

    @Test
    @Config(sdk = [29], qualifiers = "zh-rTW-w320dp-h600dp-xhdpi")
    fun traditionalChineseSettingsFitNarrowScreensWithLargeFonts() {
        RuntimeEnvironment.setFontScale(1.3f)
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        verifySettingsDoNotEllipsizeVisibleText("應用程式語言")
    }

    @Test
    @Config(sdk = [29], qualifiers = "zh-rTW-w1000dp-h700dp-xhdpi")
    fun traditionalChineseSettingsFitExpandedScreensWithLargeFonts() {
        RuntimeEnvironment.setFontScale(1.3f)
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        verifySettingsDoNotEllipsizeVisibleText("應用程式語言")
    }

    private fun verifySettingsDoNotEllipsizeVisibleText(languageLabel: String) {
        val screen = openSettings()
        assertEquals(languageLabel, screen.getString(R.string.language_app_language))

        fun verifyVisibleText() {
            layoutRoot(screen)
            for (view in texts(screen)) {
                if (view.visibility != View.VISIBLE) continue
                val lines = view.layout ?: continue
                for (line in 0 until lines.lineCount) {
                    assertEquals("Truncated localized label: ${view.text}", 0, lines.getEllipsisCount(line))
                }
            }
        }

        verifyVisibleText()
        for (category in SettingsCategory.entries.filter { it != SettingsCategory.OVERVIEW }) {
            ReflectionHelpers.callInstanceMethod<Unit>(
                screen, "openSettingsCategory",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category),
            )
            verifyVisibleText()
        }
    }

    @After fun tearDown() {
        activity?.finish()
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun compactSettingsOpenOnAnEverydayDriverOverview() {
        val screen = openSettings()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_overview) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun savedLightAppearanceThemesTheSettingsCanvasAndSystemBars() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        val screen = openSettings()
        val palette = ReflectionHelpers.getField<DiPlayPalette>(screen, "palette")
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")

        assertSame(DiPlayPalette.LIGHT, palette)
        assertEquals(DiPlayPalette.settings(false).background, (scroll.background as ColorDrawable).color)
        assertEquals(
            DiPlayPalette.settings(false).background,
            (screen.window.decorView.background as ColorDrawable).color,
        )
        assertEquals(DiPlayPalette.settings(false).systemBar, screen.window.navigationBarColor)
    }

    @Test fun appearanceIsConfiguredOnlyInDisplaySettingsAndHeaderHasNoMoon() {
        val screen = openSettings()
        assertEquals(AppAppearance.AUTO, AirPlayPersistence.loadAppAppearance(screen))
        assertFalse(descendants(screen.window.decorView).any { it.tag == "app_appearance_button" })
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DISPLAY))
        texts(screen).first { it.text.startsWith(screen.getString(R.string.settings_app_appearance) + " · ") }.performClick()
        val dialog = TestDialogs.latest()
        assertEquals(3, dialog.listView.adapter.count)
        shadowOf(dialog).clickOnItem(0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppAppearance.DARK, AirPlayPersistence.loadAppAppearance(screen))
        assertSame(DiPlayPalette.DARK, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        ReflectionHelpers.setField(screen, "page", "home")
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertFalse(descendants(screen.window.decorView).any { it.tag == "app_appearance_button" })
    }

    @Test fun autoAppearanceRepaintsWhenTheVehicleSystemThemeChanges() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.AUTO)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.NIGHT)
        val screen = openSettings()
        assertSame(DiPlayPalette.LIGHT, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        val config = android.content.res.Configuration(screen.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        @Suppress("DEPRECATION")
        screen.resources.updateConfiguration(config, screen.resources.displayMetrics)
        screen.onConfigurationChanged(config)
        assertSame(DiPlayPalette.DARK, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
    }

    @Test fun hostAppearancePublishedOffMainDoesNotOverrideVehicleSystemTheme() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.AUTO)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.DAY)
        val screen = openSettings()
        assertSame(DiPlayPalette.LIGHT, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        val owner = Any()
        val failure = AtomicReference<Throwable?>()

        val publisher = Thread {
            try {
                AppAppearanceRuntime.publishHost(owner, true)
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        publisher.start()
        publisher.join()

        assertNull(failure.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(DiPlayPalette.LIGHT, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
        AppAppearanceRuntime.clearHost(owner)
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w500dp-h400dp")
    fun appearanceRepaintKeepsTheCurrentSettingsPageAndScrollPosition() {
        val screen = openSettings()
        descendants(screen.window.decorView).single {
            it.settingsControlTitle == screen.getString(
                R.string.settings_open_category,
                screen.getString(R.string.settings_display),
            )
        }.performClick()
        layoutRoot(screen)
        val oldScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        oldScroll.scrollTo(0, oldScroll.getChildAt(0).height)
        val previousY = oldScroll.scrollY
        assertTrue(previousY > 0)

        AirPlayPersistence.saveAppAppearance(screen, AppAppearance.DARK)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "checkForAppearanceChange")
        shadowOf(Looper.getMainLooper()).idle()
        layoutRoot(screen)

        assertEquals(SettingsCategory.DISPLAY, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertEquals(previousY, ReflectionHelpers.getField<ScrollView>(screen, "rootScroll").scrollY)
    }

    @Test fun openDialogKeepsItsAppearanceAndEditsWhileTheScreenRepaints() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        val screen = openSettings()
        DiPlayActivity::class.java.getDeclaredMethod("textInput", String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Function1::class.java).apply { isAccessible = true }
            .invoke(screen, screen.getString(R.string.settings_app_appearance), "", false, { _: String -> })
        val dialog = TestDialogs.latest()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("display")
        val dialogAccent = dialog.context.theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent),
        ).let { attributes ->
            try {
                attributes.getColor(0, 0)
            } finally {
                attributes.recycle()
            }
        }

        AirPlayPersistence.saveAppAppearance(screen, AppAppearance.DARK)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "checkForAppearanceChange")

        assertTrue(dialog.isShowing)
        assertEquals("display", input.text.toString())
        assertEquals(DiPlayPalette.LIGHT.accent, dialogAccent)
        assertSame(DiPlayPalette.DARK, ReflectionHelpers.getField<DiPlayPalette>(screen, "palette"))
    }

    @Config(qualifiers = "en-w700dp-h400dp-land")
    @Test fun fullSettingsOverrideIsIndependentAndCanBeDisabledFromDisplay() {
        val screen = openSettings()
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.DISPLAY)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        fun settingsSwitch() = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.settingsControlTitle == screen.getString(R.string.settings_force_full_settings) }

        settingsSwitch().isChecked = true
        assertTrue(SettingsLayoutPreferences.forceFull(context))
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))

        settingsSwitch().isChecked = false
        assertFalse(SettingsLayoutPreferences.forceFull(context))
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))
    }

    @Config(qualifiers = "en-w400dp-h700dp-port")
    @Test fun portraitIgnoresFullSettingsOverrideWithoutClearingPreference() {
        SettingsLayoutPreferences.saveForceFull(context, true)
        val screen = openSettings()
        assertFalse(SettingsLayoutPreferences.isActive(screen))
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(screen, "isExpandedSettingsLayout"))
        assertTrue(SettingsLayoutPreferences.forceFull(context))
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h700dp-land")
    fun naturallyExpandedSettingsHideTheForceLayoutSwitch() {
        SettingsLayoutPreferences.saveForceFull(context, true)
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.DISPLAY)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertFalse(descendants(screen.window.decorView).filterIsInstance<Switch>().any {
            it.settingsControlTitle == screen.getString(R.string.settings_force_full_settings)
        })
    }

    @Test fun readinessAsksForAnIphoneBeforeWirelessCanConnect() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        val screen = openSettings()
        // Robolectric has no CarPlay authentication assets, which is a setup error of its own.
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
        texts(screen).single { it.text == screen.getString(R.string.connection_setup) }.performClick()
        assertEquals("connection", ReflectionHelpers.getField<String>(screen, "page"))
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.ready) })
    }

    @Test fun chosenPhoneWithMissingManualHotspotOffersConnectionSetup() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        DiPlayPreferences.savePhone(context, "AA:BB:CC:DD:EE:FF", "My iPhone")
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertFalse(texts(screen).any { it.text == screen.getString(R.string.ready) })
        texts(screen).single { it.text == screen.getString(R.string.connection_setup) }.performClick()
        assertEquals("connection", ReflectionHelpers.getField<String>(screen, "page"))
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun cardControlsShareOneHeightAndGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.settingsControlTitle == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        val density = screen.resources.displayMetrics.density
        fun dp(value: Int) = Math.round(value * density)
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")

        val buttons = descendants(scroll).filterIsInstance<android.widget.Button>()
            .filter { it !is Switch && (it.parent as? LinearLayout)?.orientation == LinearLayout.VERTICAL }
            .toList()

        assertTrue(buttons.any { it.text.startsWith(screen.getString(R.string.settings_app_appearance)) })
        buttons.forEach { button ->
            val lp = button.layoutParams as LinearLayout.LayoutParams
            if (button.text.contains(" · ")) {
                assertEquals(button.text.toString(), ViewGroup.LayoutParams.WRAP_CONTENT, lp.height)
                assertEquals(button.text.toString(), dp(SettingsWidgets.CARD_ROW_MIN_HEIGHT_DP), button.minimumHeight)
            } else {
                assertEquals(button.text.toString(), dp(60), lp.height)
            }
            assertEquals(button.text.toString(), 0, lp.topMargin)
            assertTrue(button.text.toString(), lp.bottomMargin in setOf(0, dp(6), dp(12)))
        }
        val page = scroll.getChildAt(0)
        assertFalse(descendants(scroll).any { it.tag == "spacer" && it.parent !== page })
    }

    @Test fun aRowBeforeAButtonKeepsTheGap() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .first { it.settingsControlTitle == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_controls_category)) }
            .performClick()
        val chooseImage = texts(screen).single { it.text == screen.getString(R.string.settings_car_phone_image) }
        val card = chooseImage.parent as LinearLayout
        val preview = card.getChildAt(card.indexOfChild(chooseImage) - 1)

        assertEquals(Math.round(12 * screen.resources.displayMetrics.density),
            (preview.layoutParams as LinearLayout.LayoutParams).bottomMargin)
    }

    @Test fun onlyTheSwitchChangesTheSetting() {
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        val screen = openSettings()
        val fullScreen = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.settingsControlTitle == screen.getString(R.string.full_screen) }

        (fullScreen.parent as View).performClick()

        // SettingsWidgets intentionally leaves row whitespace non-interactive.
        assertFalse(fullScreen.isChecked)
        assertFalse(AirPlayPersistence.loadHideTopBar(context))
        fullScreen.performClick()
        assertTrue(fullScreen.isChecked)
        assertTrue(AirPlayPersistence.loadHideTopBar(context))
    }

    @Test fun compactDetailReturnsToOverviewBeforeLeavingSettings() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.settingsControlTitle == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_carplay_picture_group) })

        screen.onBackPressedDispatcher.onBackPressed()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun openingACompactCategoryAlwaysStartsAtTheTop() {
        val screen = openSettings()
        val firstScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        firstScroll.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
        )
        firstScroll.layout(0, 0, 600, 700)
        firstScroll.scrollTo(0, 400)
        assertTrue(firstScroll.scrollY > 0)

        descendants(screen.window.decorView)
            .single { it.settingsControlTitle == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        val detailScroll = descendants(screen.window.decorView).filterIsInstance<ScrollView>().single()
        assertEquals(0, detailScroll.scrollY)
    }

    @Test fun quickFullScreenChangesBothExistingPreferencesTogether() {
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        val screen = openSettings()
        val fullScreen = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.settingsControlTitle == screen.getString(R.string.full_screen) }

        fullScreen.performClick()

        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(true, fullScreen.isChecked)
    }

    @Test fun systemBackFromConnectionSetupReturnsToConnectionSettings() {
        val screen = openConnectionSetupFromSettings()

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) })
    }

    @Test fun toolbarBackFromConnectionSetupReturnsToConnectionSettings() {
        val screen = openConnectionSetupFromSettings()

        texts(screen).single { it.text == screen.getString(R.string.back) }.performClick()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connection_setup) })
    }

    @Test fun connectionSetupOpenedDirectlyStillReturnsHome() {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "connection"),
        ).setup().get().also { activity = it }

        screen.onBackPressedDispatcher.onBackPressed()

        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
    }

    @Test fun navigationContainsClusterMapAlongsideLocation() {
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.NAVIGATION))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.carplay_map_on_instrument_cluster_experimental) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.location) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.audio_routing) })
    }

    @Test fun displayOpensPictureAdjustments() {
        val screen = openSettings()
        descendants(screen.window.decorView)
            .single { it.settingsControlTitle == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) }
            .performClick()

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.picture_adjustments) })
    }

    @Test fun settingsLiveWhereDriversLookForThem() {
        for (p7 in listOf(false, true)) {
            fun sections(category: SettingsCategory) = SettingsInformationArchitecture.sections(category, p7)
            assertTrue(SettingsSection.LOCATION in sections(SettingsCategory.NAVIGATION))
            assertTrue(SettingsSection.AUTHENTICATION in sections(SettingsCategory.CONNECTION))
            assertTrue(SettingsSection.ADVANCED_VIDEO in sections(SettingsCategory.DISPLAY))
            assertTrue(SettingsSection.EXPERIMENTAL_DISPLAY in sections(SettingsCategory.DISPLAY))
            assertTrue(SettingsSection.ADVANCED_MEDIA in sections(SettingsCategory.AUDIO))
            assertTrue(SettingsSection.NAVIGATION_WHEEL in sections(SettingsCategory.AUDIO))
            assertTrue(SettingsSection.WHEEL_KEYS in sections(SettingsCategory.CONTROLS))
            assertTrue(SettingsSection.CAR_BUTTON in sections(SettingsCategory.CONTROLS))
            assertTrue(SettingsSection.P7_CARPLAY_DATA in sections(SettingsCategory.VEHICLE))
            assertTrue(SettingsSection.P7_DIRECTION in sections(SettingsCategory.NAVIGATION))
            assertTrue(SettingsSection.VEHICLE_EXPERIMENT in sections(SettingsCategory.DIAGNOSTICS))
            assertTrue(SettingsSection.LANGUAGE in sections(SettingsCategory.ABOUT))
        }
    }

    @Test fun usbChargingIsSavedForTheNextWiredConnectionWithoutDroppingTheSession() {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            PendingReconnect.clear()
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.CONNECTION)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val row = texts(screen).single { it.text.startsWith(screen.getString(R.string.settings_usb_charging)) }
            assertEquals("${screen.getString(R.string.settings_usb_charging)} · ${screen.getString(R.string.settings_usb_charging_normal)}", row.text.toString())
            row.performClick()
            val dialog = TestDialogs.latest()
            assertEquals(screen.getString(R.string.save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            shadowOf(dialog).clickOnItem(UsbChargingCurrent.entries.indexOf(UsbChargingCurrent.LOW))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(UsbChargingCurrent.LOW, AirPlayPersistence.loadUsbChargingCurrent(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(0, stops)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [28, 33])
    fun experimentalCallProcessingIsOptInAndMarksTheActiveSessionForReconnect() {
        assertFalse(AirPlayPersistence.loadCallEchoCancellation(context))
        assertFalse(AirPlayPersistence.loadCallVoiceFilter(context))
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.AUDIO)
            listOf(R.string.call_echo_cancellation, R.string.call_voice_filter).forEach { title ->
                PendingReconnect.clear()
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
                    .single { it.settingsControlTitle == screen.getString(title) }
                assertFalse(setting.isChecked)
                setting.performClick()

                assertTrue(PendingReconnect.isPending(session))
                assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
                assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
                assertEquals(0, stops)
                assertEquals(null, shadowOf(screen).nextStartedActivity)
            }
            assertTrue(AirPlayPersistence.loadCallEchoCancellation(context))
            assertTrue(AirPlayPersistence.loadCallVoiceFilter(context))
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [28])
    fun mtkDecoderTuningAppearsOnlyForMtkAndMarksReconnect() {
        AirPlayPersistence.saveMtkDecoderTuning(context, false)
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.DISPLAY)
        screen.selectedMtkDecoderName = { null }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_mtk_decoder_tuning) })

        val decoderName = "OMX.MTK.VIDEO.DECODER.HEVC"
        screen.selectedMtkDecoderName = { decoderName }
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        assertTrue(texts(screen).any {
            it.text == screen.getString(R.string.settings_mtk_decoder_tuning_description, decoderName)
        })

        val session = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        CarPlayBackgroundSession.active = true
        try {
            val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
                .single { it.settingsControlTitle == screen.getString(R.string.settings_mtk_decoder_tuning) }
            assertFalse(setting.isChecked)
            setting.performClick()

            assertTrue(AirPlayPersistence.loadMtkDecoderTuning(context))
            assertTrue(PendingReconnect.isPending(session))
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        } finally {
            AirPlayPersistence.saveMtkDecoderTuning(context, false)
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [28, 33])
    fun disablingMediaKeyAudioFocusIsOptInAndMarksTheActiveSessionForReconnect() {
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, false)
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.AUDIO)
            PendingReconnect.clear()
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
                .single { it.settingsControlTitle == screen.getString(R.string.settings_disable_media_key_audio_focus) }
            assertFalse(setting.isChecked)

            setting.performClick()

            assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(0, stops)
            assertEquals(null, shadowOf(screen).nextStartedActivity)
        } finally {
            AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, false)
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [28, 33])
    fun theAudioFocusAutoYieldSwitchMarksTheActiveSessionForReconnect() {
        // The sink reads this flag once, when it is built, so the change lands on the next session.
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(context))
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.AUDIO)
            PendingReconnect.clear()
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val setting = descendants(screen.window.decorView).filterIsInstance<Switch>()
                .single { it.settingsControlTitle == screen.getString(R.string.audio_focus_auto_yield) }
            assertTrue(setting.isChecked)
            setting.performClick()

            assertFalse(AirPlayPersistence.loadAudioFocusAutoYield(context))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(0, stops)
            assertEquals(null, shadowOf(screen).nextStartedActivity)
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailStaysOutsideTheScrollingCategory() {
        val screen = openSettings()
        val categoryScroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        val railDestination = descendants(screen.window.decorView).first {
            it.settingsControlTitle == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.settings_display)) &&
                descendants(it).filterIsInstance<ImageView>().count() == 1
        }

        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_quick_settings) && isInside(it, categoryScroll) })
        assertFalse(isInside(railDestination, categoryScroll))
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    fun expandedRailUsesAnIconForEveryDestination() {
        val screen = openSettings()
        val rail = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
        val destinations = descendants(rail).filter { destination ->
            destination.settingsControlTitle?.startsWith("Open ") == true &&
                destination.settingsControlTitle?.endsWith(" settings") == true &&
                descendants(destination).filterIsInstance<ImageView>().count() == 1
        }.toList()
        assertEquals(SettingsCategory.entries.size, destinations.size)
        destinations.forEach { destination ->
            assertEquals(1, descendants(destination).filterIsInstance<ImageView>().count())
        }
    }

    @Test fun compactOverviewCategoriesHaveIcons() {
        val screen = openSettings()
        listOf(SettingsCategory.CONNECTION, SettingsCategory.DISPLAY, SettingsCategory.AUDIO,
            SettingsCategory.NAVIGATION, SettingsCategory.VEHICLE).forEach { category ->
            val title = ReflectionHelpers.callInstanceMethod<String>(screen, "settingsCategoryTitle",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
            val destination = descendants(screen.window.decorView).single {
                it.settingsControlTitle == screen.getString(R.string.settings_open_category, title)
            }
            assertEquals(1, descendants(destination).filterIsInstance<ImageView>().count())
        }
    }

    @Test fun overviewLinksToEveryOtherCategory() {
        val screen = openSettings()
        (SettingsCategory.entries - SettingsCategory.OVERVIEW).forEach { category ->
            val title = ReflectionHelpers.callInstanceMethod<String>(screen, "settingsCategoryTitle",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
            assertTrue(category.name, descendants(screen.window.decorView).any {
                it.settingsControlTitle == screen.getString(R.string.settings_open_category, title)
            })
        }
    }

    @Test
    @Config(qualifiers = "en-w1280dp-h700dp-land")
    fun settingsHeaderHasNoSearchAndBackReturnsToOverview() {
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.DISPLAY))
        assertFalse(descendants(screen.window.decorView).any {
            it.tag == "settings_search_button" || it is EditText
        })
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals(SettingsCategory.OVERVIEW,
            ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
    }

    @Test fun compactLanguageAndAboutDestinationsWork() {
        verifyLanguageAndAboutDestinations(expanded = false)
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h700dp")
    fun expandedLanguageAndAboutDestinationsWork() {
        verifyLanguageAndAboutDestinations(expanded = true)
    }

    private fun verifyLanguageAndAboutDestinations(expanded: Boolean) {
        val screen = openSettings()
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.ABOUT))
        assertEquals(SettingsCategory.ABOUT, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(descendants(screen.window.decorView).any {
            it.settingsControlTitle == screen.getString(R.string.language_app_language)
        })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.backup_open) })
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals(SettingsCategory.OVERVIEW, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
    }

    @Test
    @Config(sdk = [29], qualifiers = "en-w1000dp-h400dp")
    fun selectingTheBottomRailDestinationKeepsItVisibleAndFocusedOnAShortScreen() {
        val screen = openSettings()
        val density = screen.resources.displayMetrics.density
        fun layout() {
            val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val width = Math.round(1000 * density)
            val height = Math.round(400 * density)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
        }
        fun rail() = ReflectionHelpers.getField<ScrollView>(screen, "settingsRailScroll")
        fun advanced(scroll: ScrollView) = descendants(scroll).single {
            it.settingsControlTitle == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.about))
        }
        layout()
        val previousRail = rail()
        previousRail.scrollTo(0, previousRail.getChildAt(0).height)
        assertTrue("This window must require rail scrolling", previousRail.scrollY > 0)
        val destination = advanced(previousRail)
        assertTrue(destination.requestFocus())
        assertTrue(previousRail.hasFocus())

        destination.performClick()
        layout()

        val currentRail = rail()
        val selected = advanced(currentRail)
        assertFalse(previousRail === currentRail)
        assertTrue(selected.isSelected)
        assertTrue("D-pad focus must follow the selected category", selected.isFocused)
        assertTrue(currentRail.scrollY > 0)
        val top = selected.top + currentRail.getChildAt(0).top
        assertTrue("Selected row starts inside the rail viewport", top >= currentRail.scrollY)
        assertTrue("Selected row ends inside the rail viewport",
            top + selected.height <= currentRail.scrollY + currentRail.height)
        assertEquals("Category scrolling remains independent", 0,
            ReflectionHelpers.getField<ScrollView>(screen, "rootScroll").scrollY)
        val railContent = currentRail.getChildAt(0) as ViewGroup
        val railPanel = railContent.getChildAt(0)
        currentRail.scrollTo(0, railContent.height)
        val panelBottom = railContent.top + railPanel.bottom - currentRail.scrollY
        assertEquals("Bottom spacing is reachable inside the scroll content",
            Math.round(12 * density), currentRail.height - panelBottom)
    }

    @Test fun overviewEndsWithDiagnosticsAndAbout() {
        val screen = openSettings()
        val destinations = descendants(screen.window.decorView).mapNotNull {
            it.settingsControlTitle?.toString()
        }.filter { it.startsWith("Open ") }.toList()
        val about = screen.getString(R.string.settings_open_category, screen.getString(R.string.about))
        val diagnostics = screen.getString(R.string.settings_open_category, screen.getString(R.string.diagnostics))
        assertEquals(destinations.indexOf(diagnostics) + 1, destinations.indexOf(about))
        assertEquals(destinations.last(), about)
    }
    private fun installBydSettingsPackage() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }

    // The report is troubleshooting evidence: its way onto a phone starts in Diagnostics and returns there.
    @Test fun diagnosticsOpensThePhonePageAndBackReturnsToDiagnostics() {
        val screen = openSettings()
        descendants(screen.window.decorView).first { candidate ->
            candidate.settingsControlTitle == screen.getString(R.string.settings_open_category, screen.getString(R.string.diagnostics))
        }.performClick()
        texts(screen).first { it.text == screen.getString(R.string.settings_report_on_phone) }.performClick()
        // The phone page, under the name the driver tapped.
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_report_on_phone) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.backup_title) })
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.save_diagnostic_report) })
        screen.onBackPressedDispatcher.onBackPressed()
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.save_diagnostic_report) })
    }

    @Test
    @Config(qualifiers = "en-w1280dp-h700dp")
    fun overviewUsesTwoModulesWithoutDuplicatingRailDestinations() {
        val screen = openSettings()
        val modules = descendants(screen.window.decorView).single { it.tag == "overview_modules" } as LinearLayout
        assertEquals(LinearLayout.HORIZONTAL, modules.orientation)
        val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
        (SettingsCategory.entries - SettingsCategory.OVERVIEW).forEach { category ->
            val title = ReflectionHelpers.callInstanceMethod<String>(screen, "settingsCategoryTitle", ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
            assertFalse(descendants(scroll).any { it.settingsControlTitle == screen.getString(R.string.settings_open_category, title) })
        }
        assertFalse(texts(screen).any { it.text == screen.getString(R.string.settings_your_setup) })
        assertFalse(descendants(scroll).filterIsInstance<Switch>().any {
            it.settingsControlTitle == screen.getString(R.string.report_location_to_iphone)
        })
        val autoConnect = descendants(scroll).filterIsInstance<Switch>().single {
            it.settingsControlTitle?.toString()?.startsWith(screen.getString(R.string.connect_when_diplay_opens)) == true
        }
        val wasEnabled = DiPlayPreferences.autoConnect(screen)
        autoConnect.performClick()
        assertEquals(!wasEnabled, DiPlayPreferences.autoConnect(screen))
    }

    @Test fun blockedConnectionShowsItsReasonInsteadOfSilentlyDisablingTheButton() {
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "setupError", "Authentication resources missing")
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val connect = texts(screen).single { it.text == screen.getString(R.string.connect_phone) }
        assertTrue(connect.isEnabled)
        connect.performClick()
        val dialog = TestDialogs.latest()
        assertTrue(dialog.isShowing)
        assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString().contains("Authentication resources missing")
        })
        assertNull(shadowOf(screen).nextStartedActivity)
        dialog.dismiss()
    }

    @Test fun carPlayReturnOpensOverviewAfterAnotherCategory() {
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.AUDIO)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "onNewIntent",
            ReflectionHelpers.ClassParameter(Intent::class.java, Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")))
        assertEquals(SettingsCategory.OVERVIEW, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connect_phone) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.connect_with_usb) })
    }

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { activity = it }

    private fun openConnectionSetupFromSettings(): DiPlayActivity = openSettings().also { screen ->
        descendants(screen.window.decorView)
            .single { it.settingsControlTitle == screen.getString(R.string.settings_open_category,
                screen.getString(R.string.connection)) }
            .performClick()
        texts(screen).single { it.text == screen.getString(R.string.open_connection_setup) }.performClick()
        assertEquals("connection", ReflectionHelpers.getField<String>(screen, "page"))
    }

    private fun layoutRoot(screen: DiPlayActivity) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun isInside(view: View, ancestor: View): Boolean =
        generateSequence(view.parent) { it.parent }.any { it === ancestor }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
