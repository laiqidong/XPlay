package com.shilapi.xcertplay

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", manifest = Config.NONE)
class AppLocaleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun oldAppLanguageIsRetiredWithoutChangingSystemLanguage() {
        val prefs = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        prefs.edit().putString("app_language", "ar").putBoolean("app_language_platform_migrated", true).commit()
        val systemLocales = Resources.getSystem().configuration.locales
        assertSame(context, AppLocale.wrap(context))
        assertFalse(prefs.contains("app_language"))
        assertFalse(prefs.contains("app_language_platform_migrated"))
        assertEquals(systemLocales, Resources.getSystem().configuration.locales)
        assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
    }

    @Test @Config(sdk = [33])
    fun platformOverrideIsClearedToFollowTheHeadUnit() {
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.forLanguageTags("ar")
        AppLocale.wrap(context)
        assertTrue(manager.applicationLocales.isEmpty)
    }

    @Test fun staleContextLocaleIsRestoredWithoutChangingDensity() {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale("ar")) }
        val wrapped = context.createConfigurationContext(configuration)
        val density = wrapped.resources.configuration.densityDpi
        assertTrue(AppLocale.enforce(wrapped))
        assertEquals(Resources.getSystem().configuration.locales, wrapped.resources.configuration.locales)
        assertEquals(density, wrapped.resources.configuration.densityDpi)
        assertFalse(AppLocale.enforce(wrapped))
    }
}
