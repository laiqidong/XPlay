package com.shilapi.xcertplay

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import com.shilapi.xcertplay.host.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** One in-memory sample per dialog; no files, and no sampling of the dialog itself. */
internal object DialogBackdrop {
    private const val SAMPLE_WIDTH = 360
    private val worker = Executors.newSingleThreadExecutor { job -> Thread(job, "xplay-dialog-blur") }
    private val main = Handler(Looper.getMainLooper())

    class Frame(val left: Int, val top: Int, val width: Int, val height: Int) {
        val closed = AtomicBoolean(false)
        var bitmap: Bitmap? = null
    }

    fun frame(view: View): Frame? = view.rootView.getTag(R.id.glass_dialog_capture) as? Frame

    fun attach(dialog: Dialog, context: Context) {
        val decor = dialog.window?.decorView ?: return
        if (decor.getTag(R.id.glass_dialog_capture) != null) return
        val activity = activity(context) ?: return
        val source = activity.window.decorView
        if (source.width <= 0 || source.height <= 0) return
        val position = IntArray(2)
        source.getLocationOnScreen(position)
        val frame = Frame(position[0], position[1], source.width, source.height)
        decor.setTag(R.id.glass_dialog_capture, frame)
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                frame.closed.set(true)
                frame.bitmap = null
                v.removeOnAttachStateChangeListener(this)
            }
        })
        val width = minOf(SAMPLE_WIDTH, source.width)
        val height = (source.height.toFloat() * width / source.width).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fun publish() {
            worker.execute {
                if (frame.closed.get()) { bitmap.recycle(); return@execute }
                SettingsBackdrop.blurDialogSnapshot(bitmap)
                main.post {
                    if (frame.closed.get()) bitmap.recycle()
                    else {
                        frame.bitmap = bitmap
                        fun invalidateTree(view: View) {
                            view.invalidate()
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) invalidateTree(view.getChildAt(i))
                        }
                        invalidateTree(decor)
                    }
                }
            }
        }
        fun drawNativePage() {
            if (frame.closed.get()) { bitmap.recycle(); return }
            val canvas = Canvas(bitmap)
            canvas.scale(width.toFloat() / source.width, height.toFloat() / source.height)
            source.draw(canvas)
            publish()
        }
        // PixelCopy keeps readback off the UI drawing path and includes TextureView content.
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PixelCopy.request(activity.window, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) publish() else drawNativePage()
                }, main)
            } catch (_: IllegalArgumentException) { drawNativePage() }
        } else drawNativePage()
    }

    private fun activity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return current as? Activity
    }
}
