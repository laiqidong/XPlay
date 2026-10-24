package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Debug-only native preview. Uses the real layout/material and a local screenshot, never vehicle ADB. */
class ClimatePreviewActivity : Activity() {
    private var overlay: P7ClimateOverlay? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = Configuration(resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        @Suppress("DEPRECATION") resources.updateConfiguration(config, resources.displayMetrics)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val bitmap = BitmapFactory.decodeFile(File(filesDir, "climate-preview-background.png").path)
        if (bitmap == null) {
            root.addView(TextView(this).apply { text = "Please copy climate-preview-background.png into app files."; setTextColor(Color.WHITE) })
            return
        }
        val background = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(bitmap) }
        root.addView(background, FrameLayout.LayoutParams(-1, -1))
        val artwork = File(filesDir, "climate-preview-oem.apk")
        val iconResources = if (artwork.isFile) runCatching {
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val cookie = AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, artwork.path) as Int
            check(cookie != 0)
            @Suppress("DEPRECATION") Resources(assets, resources.displayMetrics, resources.configuration)
        }.getOrNull() else null
        overlay = P7ClimateOverlay(root, eligible = { true }, source = { background },
            videoBounds = { RectF(0f, 0f, root.width.toFloat(), root.height.toFloat()) }, cancelTouch = {},
            supported = { true }, iconLoader = { c, key -> ClimateIcons.load(c, key, iconResources) },
            sessionFactory = { _, changed, _ -> PreviewSession(changed) })
        P7ClimateOverlay.enable(this, true)
        root.post { overlay?.resume(); overlay?.open() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    override fun onResume() { super.onResume(); overlay?.resume() }
    override fun onPause() { overlay?.pause(); super.onPause() }
    override fun onDestroy() { overlay?.close(); super.onDestroy() }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (overlay?.dismiss() != true) super.onBackPressed() }

    private class PreviewSession(private val changed: (JSONObject?) -> Unit) : P7ClimateSession {
        private val data = JSONObject("""{"ok":true,"driver":25.0,"passenger":24.0,"fan":4,"power":1,"ac":7,"auto":1,"sync":3,"cycle":1,"direction":1,"front":0,"rear":0,"eco":0,"max":null,"aqs":1,"defog":1}""")
        private var active = false
        override fun start() { active = true; changed(JSONObject(data.toString())) }
        override fun stop() { active = false }
        override fun close() { stop() }
        override fun request(command: String): Boolean {
            if (!active) return false
            // Local-only values for exercising the controls during layout work.
            val parts = command.lowercase(Locale.ROOT).split('_')
            val key = parts.first()
            val value = parts.last()
            when {
                key in listOf("driver", "passenger") -> data.put(key, (data.optDouble(key) + if (value == "up") .5 else -.5).coerceIn(18.0, 32.0))
                command.startsWith("FAN_LEVEL_") -> { data.put("fan", value.toInt().coerceIn(1, 10)); data.put("auto", 0) }
                key == "fan" -> data.put(key, ((if (data.optInt(key) == 14) 3 else data.optInt(key)) + if (value == "up") 1 else -1).coerceIn(1, 10))
                key == "ac" -> data.put("ac", when(value) { "cool" -> 1; "heat" -> 3; "natural" -> 5; else -> 7 })
                key == "cycle" -> data.put(key, when (value) { "inner" -> 1; "outside" -> 2; else -> 6 })
                key == "direction" -> data.put(key, when (value) { "window" -> 6; "face" -> 1; else -> 3 })
                key == "sync" -> data.put(key, if (value == "on") 1 else 3)
                key != "read" -> data.put(key, if (value == "on") 1 else 0)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (active) changed(JSONObject(data.toString())) }
            return true
        }
    }
}
