package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Surface
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class CarButtonImagePickerTest {
    // carButtonControls and buildAirPlayIconSection now use showCarImageUpload;
    // keep testing cancellation, crop URI grants and host external-state cleanup.
    @Test fun carButtonOffersPhoneUploadWithoutOpeningAnAndroidPicker() = withHome { activity ->
        val choose = homeButtons(activity).single { it.text == activity.getString(R.string.settings_car_phone_image) }
        choose.performClick()
        val dialog = requireNotNull(TestDialogs.latestRaw())
        assertTrue(dialog.isShowing)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        dialog.dismiss()
    }

    @Test fun cancellingPhoneUploadPreservesTheSavedImage() = withHome { activity ->
        val previous = AirPlayPersistence.loadCustomAirPlayIconFile(activity)
        homeButtons(activity).single { it.text == activity.getString(R.string.settings_car_phone_image) }.performClick()
        requireNotNull(TestDialogs.latestRaw()).dismiss()
        assertEquals(previous, AirPlayPersistence.loadCustomAirPlayIconFile(activity))
    }

    @Test fun hostPhoneUploadKeepsTheExistingCropFlow() = withHost { activity ->
        var cropIntent: Intent? = null
        ReflectionHelpers.setField(activity, "imageCrop", launcher(ActivityResultContracts.StartActivityForResult()) { cropIntent = it })
        val uri = Uri.parse("content://test-images/icon")
        ReflectionHelpers.setField(activity, "externalActivityInProgress", true)
        crop(activity, uri)
        assertEquals(uri, cropIntent!!.data)
        assertEquals(ImageCropActivity::class.java.name, cropIntent!!.component!!.className)
        assertTrue(cropIntent!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        crop(activity, null)
        assertFalse(externalActivity(activity))
    }

    @Test fun hostPhoneUploadReleasesExternalStateWhenClosed() = withHost { activity ->
        hostButtons(activity).single { it.text == activity.getString(R.string.settings_car_phone_image) }.performClick()
        val dialog = requireNotNull(TestDialogs.latestRaw())
        assertTrue(dialog.isShowing)
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(externalActivity(activity))
    }

    @Test fun otherDocumentFailuresDoNotLaunchASecondPicker() {
        val denied = SecurityException("No document access")
        var contentLaunches = 0
        val result = launchCarButtonImagePicker(
            openDocument = { throw denied },
            getContent = { contentLaunches++ },
        )
        assertSame(denied, result.exceptionOrNull())
        assertEquals(0, contentLaunches)
    }

    @Test fun withoutASystemDocumentPickerTheContentPickerGoesFirst() {
        val events = mutableListOf<String>()
        launchCarButtonImagePicker(
            openDocument = { events.add("document") },
            getContent = { events.add("content"); throw ActivityNotFoundException("No content picker") },
            documentPickerIsSystem = false,
        )
        assertEquals(listOf("content", "document"), events)
    }

    @Test fun onlyNonSystemDocumentHandlersCountAsNoSystemPicker() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(context.documentPickerIsSystem())
        val intent = ActivityResultContracts.OpenDocument().createIntent(context, arrayOf("image/*"))
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, handler("app.interceptor", 0))
        assertFalse(context.documentPickerIsSystem())
        shadowOf(context.packageManager).addResolveInfoForIntent(
            intent, handler("com.android.documentsui", ApplicationInfo.FLAG_SYSTEM))
        assertTrue(context.documentPickerIsSystem())
    }

    private fun handler(packageName: String, flags: Int) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.Picker"
            applicationInfo = ApplicationInfo().apply { this.packageName = packageName; this.flags = flags }
        }
        match = IntentFilter.MATCH_CATEGORY_TYPE
    }

    @Test fun savingCarButtonNameMarksTheActiveSessionWithoutStoppingIt() = withHome { activity ->
        val session = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        val previousName = AirPlayPersistence.loadOemLabel(activity)
        var stops = 0
        CarPlayBackgroundSession.store(session, sink, 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {
            stops++
        }
        CarPlayBackgroundSession.active = true
        PendingReconnect.clear()
        try {
            ReflectionHelpers.setField(activity, "page", "settings")
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.VEHICLE)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val bar = ReflectionHelpers.getField<View>(activity, "reconnectBar")
            assertEquals(View.GONE, bar.visibility)
            val name = views(activity.window.decorView).filterIsInstance<Button>().single {
                it.text.startsWith(activity.getString(R.string.car_button_name) + " · ")
            }
            name.performClick()
            val dialog = requireNotNull(TestDialogs.latest())
            views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("My car")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("My car", AirPlayPersistence.loadOemLabel(activity))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(View.VISIBLE, bar.visibility)
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(0, stops)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            AirPlayPersistence.saveOemLabel(activity, previousName)
            PendingReconnect.clear()
        }
    }

    private fun assertDocumentFilter(intent: Intent) {
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(listOf("image/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        assertTrue(!intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    private fun homeButtons(activity: DiPlayActivity): List<Button> {
        val controls = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("carButtonControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, controls)
        return views(controls).filterIsInstance<Button>().toList()
    }

    private fun hostButtons(activity: CarPlayHostActivity): List<Button> {
        val section = CarPlayHostActivity::class.java.getDeclaredMethod("buildAirPlayIconSection")
            .apply { isAccessible = true }.invoke(activity) as View
        return views(section).filterIsInstance<Button>().toList()
    }

    private fun crop(activity: CarPlayHostActivity, uri: Uri?) {
        CarPlayHostActivity::class.java.getDeclaredMethod("cropSelectedImage", Uri::class.java)
            .apply { isAccessible = true }.invoke(activity, uri)
    }

    private fun externalActivity(activity: CarPlayHostActivity): Boolean =
        ReflectionHelpers.getField(activity, "externalActivityInProgress")

    private fun <I, O> launcher(contract: ActivityResultContract<I, O>, onLaunch: (I) -> Unit) =
        object : ActivityResultLauncher<I>() {
            override fun launch(input: I, options: ActivityOptionsCompat?) = onLaunch(input)
            override fun unregister() = Unit
            override fun getContract() = contract
        }

    private fun withHome(check: (DiPlayActivity) -> Unit) {
        CarPlayBackgroundSession.clear()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup().visible()
        try {
            check(activity)
        } finally {
            controller.pause().stop().destroy()
            CarPlayBackgroundSession.clear()
        }
    }

    private fun withHost(check: (CarPlayHostActivity) -> Unit) {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try { check(activity) } finally {
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<ExecutorService>(activity, name).shutdownNow()
            }
            ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
            CarPlayBackgroundSession.clear()
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
