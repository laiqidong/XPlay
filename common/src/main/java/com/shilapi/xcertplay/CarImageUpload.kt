package com.shilapi.xcertplay

import android.app.Dialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shilapi.xcertplay.backup.SettingsBackupServer
import com.shilapi.xcertplay.backup.SettingsImportOutcome
import com.shilapi.xcertplay.backup.SettingsQrCode
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Short-lived image-only local endpoint. The existing token/code transport bounds all requests. */
internal fun ComponentActivity.showCarImageUpload(onClosed: () -> Unit = {}, onSelected: (Uri) -> Unit): Dialog? {
    val addresses = SettingsBackupServer.addressCandidates()
    if (addresses.isEmpty()) {
        appDialogBuilder().setTitle(R.string.settings_car_phone_image).setMessage(R.string.backup_no_network)
            .setPositiveButton(R.string.close, null).show()
        onClosed()
        return null
    }
    val colors = DiPlayPalette.of((this as? AppAppearanceOwner)?.currentAppNight ?: resolveAppNightNow())
    val main = Handler(Looper.getMainLooper())
    val closed = AtomicBoolean(false)
    val received = AtomicBoolean(false)
    val uploadFile = runCatching {
        val uploadDirectory = File(cacheDir, "car-icon-upload").apply { mkdirs() }
        uploadDirectory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60_000L }?.forEach { it.delete() }
        File.createTempFile("car-icon-", ".image", uploadDirectory)
    }.getOrElse {
        appDialogBuilder().setTitle(R.string.settings_car_phone_image).setMessage(R.string.settings_car_upload_failed)
            .setPositiveButton(R.string.close, null).show()
        onClosed()
        return null
    }
    lateinit var dialog: Dialog
    fun copy(id: Int) = android.text.TextUtils.htmlEncode(getString(id))
    val html = """<!doctype html><html lang="${resources.configuration.locales[0].language}" dir="${if (resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"}">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8">
<style>:root{color-scheme:light dark;--bg:#fff;--card:#f7f7f7;--fg:#272727;--blue:#0070ff}@media(prefers-color-scheme:dark){:root{--bg:#292626;--card:#2e2d2d;--fg:#dedede;--blue:#0091ff}}body{background:var(--bg);color:var(--fg);font:17px/1.6 system-ui;margin:24px auto;padding:0 20px;max-width:520px}main{background:var(--card);padding:24px;border-radius:16px}button{background:var(--blue);color:white;border:0;border-radius:12px;padding:16px;width:100%;font:inherit}button:disabled{opacity:.5}input{display:none}</style>
<main><h2>${copy(R.string.settings_car_phone_image)}</h2><p>${copy(R.string.settings_car_phone_image_hint)}</p><button id="upload">${copy(R.string.settings_car_upload)}</button><input id="file" type="file" accept="image/*"><p id="status" role="status"></p>
<p id="busy" hidden>${copy(R.string.settings_car_upload_busy)}</p><p id="ok" hidden>${copy(R.string.settings_car_upload_ok)}</p><p id="fail" hidden>${copy(R.string.settings_car_upload_failed)}</p></main>
<script>const b=document.getElementById('upload'),f=document.getElementById('file'),s=document.getElementById('status');b.onclick=()=>f.click();f.onchange=async()=>{if(!f.files.length)return;b.disabled=true;s.textContent=document.getElementById('busy').textContent;let u;try{u=URL.createObjectURL(f.files[0]);const img=new Image();await new Promise((resolve,reject)=>{img.onload=resolve;img.onerror=reject;img.src=u});const scale=Math.min(1,1600/Math.max(img.width,img.height));const c=document.createElement('canvas');c.width=Math.max(1,Math.round(img.width*scale));c.height=Math.max(1,Math.round(img.height*scale));const ctx=c.getContext('2d');ctx.fillStyle='#fff';ctx.fillRect(0,0,c.width,c.height);ctx.drawImage(img,0,0,c.width,c.height);const blob=await new Promise(resolve=>c.toBlob(resolve,'image/jpeg',.86));if(!blob||blob.size>1900000)throw Error();const r=await fetch('image',{method:'POST',body:blob});if(!r.ok)throw Error();s.textContent=document.getElementById('ok').textContent;}catch(e){s.textContent=document.getElementById('fail').textContent;b.disabled=false;}finally{if(u)URL.revokeObjectURL(u);}};</script></html>""".toByteArray(Charsets.UTF_8)
    val server = SettingsBackupServer(page = html, export = { ByteArray(0) },
        import = { SettingsImportOutcome(0, 0, true) },
        codePage = assets.open("settings-code.html").use { it.readBytes() },
        uploadImage = { bytes ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val valid = bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000L
            if (!valid || closed.get() || !received.compareAndSet(false, true)) false
            else {
                val saved = runCatching { uploadFile.writeBytes(bytes) }.isSuccess
                if (!saved) received.set(false)
                saved
            }
        })
    val endpoint = server.start()
    if (endpoint == null) {
        server.stop(); uploadFile.delete(); onClosed()
        appDialogBuilder().setTitle(R.string.settings_car_phone_image).setMessage(R.string.backup_no_network)
            .setPositiveButton(R.string.close, null).show()
        return null
    }
    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@showCarImageUpload).apply {
            text = getString(R.string.settings_car_phone_image_hint); textSize = 17f; setTextColor(colors.secondaryText)
        })
        addView(ImageView(this@showCarImageUpload).apply {
            setImageBitmap(SettingsQrCode.encode(endpoint.url(addresses.first()), dp(240)))
            contentDescription = getString(R.string.settings_car_phone_image)
        }, LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        addView(TextView(this@showCarImageUpload).apply {
            text = getString(R.string.backup_type_address, endpoint.typedAddress(addresses.first()), endpoint.code.chunked(3).joinToString(" "))
            textSize = 16f; setTextColor(colors.primaryText); setPadding(0, dp(12), 0, 0)
        })
    }
    dialog = appDialogBuilder().setTitle(R.string.settings_car_phone_image).setView(panel)
        .setNegativeButton(R.string.cancel, null).create()
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) dialog.dismiss() }
    lifecycle.addObserver(observer)
    dialog.setOnDismissListener {
        closed.set(true); server.stop(); lifecycle.removeObserver(observer)
        if (!received.get()) uploadFile.delete()
        onClosed()
    }
    server.onImageUploaded = { main.post {
        if (!closed.get() && !isFinishing && !isDestroyed) {
            dialog.dismiss()
            onSelected(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.update-apks", uploadFile))
        }
    } }
    server.onIdle = { main.post { dialog.dismiss() } }
    server.onCodeEntryClosed = { main.post { if (!closed.get()) dialog.dismiss() } }
    dialog.show()
    return dialog
}
