package com.shilapi.xcertplay.update

import androidx.core.content.FileProvider
import com.shilapi.xcertplay.FileProviderPathTestShadow
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The legacy authority now shares only car-icon uploads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], shadows = [FileProviderPathTestShadow::class])
class CarImageProviderTest {
    @Before fun resetFileProviderCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }
    @Test fun providerRejectsSiblingDirectoriesWithTheSameUpdatePrefix() {
        val context = RuntimeEnvironment.getApplication()
        val outside = File(context.cacheDir, "update-other/DiPlay.apk")
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.update-apks", outside)
        }
    }
    @Test fun retiredUpdateDirectoryIsNoLongerShared() {
        val context = RuntimeEnvironment.getApplication()
        val apk = File(context.cacheDir, "update/test/XPlay.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.update-apks", apk)
        }
    }
    @Test fun imageUploadSharesOnlyItsOwnCacheDirectory() {
        val context = RuntimeEnvironment.getApplication()
        val image = File(context.cacheDir, "car-icon-upload/test.image").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-apks", image)
        assertEquals("content", uri.scheme)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.update-apks", File(context.cacheDir, "private/identity.pk8"))
        }
    }
}
