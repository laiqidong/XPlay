package com.shilapi.xcertplay

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import com.shilapi.xcertplay.backup.SettingsBackupEndpoint
import com.shilapi.xcertplay.backup.SettingsBackupServer
import com.shilapi.xcertplay.backup.SettingsImportOutcome
import com.shilapi.xcertplay.backup.SettingsQrCode
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Dialog-scoped local transfer server. Network discovery and QR encoding never block its first frame. */
internal fun ComponentActivity.showPhoneTransferDialog(
    forReport: Boolean,
    report: () -> ByteArray,
    onImported: (SettingsImportOutcome) -> Unit,
    onClosed: () -> Unit,
): AlertDialog {
    val closed = AtomicBoolean(false)
    val server = AtomicReference<SettingsBackupServer?>(null)
    val night = (this as? AppAppearanceOwner)?.currentAppNight ?: resolveAppNightNow()
    val colors = DiPlayPalette.of(night)
    fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    fun text(value: String, primary: Boolean = false) = TextView(this).apply {
        text = value; textSize = 16f; typeface = AppFonts.regular(this@showPhoneTransferDialog)
        setTextColor(if (primary) colors.primaryText else colors.secondaryText)
    }
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(text(getString(if (forReport) R.string.settings_report_on_phone_hint else R.string.backup_page_hint)))
    val progress = ProgressBar(this)
    body.addView(progress, LinearLayout.LayoutParams(dp(TRANSFER_LOADING_DP), dp(TRANSFER_LOADING_DP)).apply {
        gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(TRANSFER_GAP_DP)
    })
    val qrSide = dp(minOf(TRANSFER_QR_DP, (resources.configuration.screenWidthDp - TRANSFER_WINDOW_INSET_DP).coerceAtLeast(1)))
    val qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
    body.addView(qr, LinearLayout.LayoutParams(qrSide, qrSide).apply {
        gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(TRANSFER_GAP_DP)
    })
    val summary = text("", true).apply { visibility = View.GONE }
    val address = text("", true).apply { visibility = View.GONE }
    val alternatives = text("").apply { visibility = View.GONE }
    for (view in listOf(summary, address, alternatives)) body.addView(view,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TRANSFER_GAP_DP) })
    var endpoint: SettingsBackupEndpoint? = null
    var addresses = emptyList<String>()
    fun updateAddress() {
        val active = endpoint ?: return
        address.text = if (server.get()?.codeEntryOpen == true)
            getString(R.string.backup_type_address, active.typedAddress(addresses.first()), active.code.chunked(3).joinToString(" "))
        else getString(R.string.backup_code_entry_closed)
    }
    val dialog = appDialogBuilder()
        .setTitle(if (forReport) R.string.settings_report_on_phone else R.string.backup_title)
        .setView(body)
        .setPositiveButton(R.string.close, null)
        .setOnDismissListener {
            closed.set(true)
            server.getAndSet(null)?.let { active -> Thread({ active.stop() }, "xplay-transfer-close").start() }
            onClosed()
        }.create()
    dialog.show()
    // Wait until the lightweight dialog has had an opportunity to draw before preparing its data.
    body.post {
        if (!closed.get()) Thread({
            var created: SettingsBackupServer? = null
            try {
                val prefix = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
                    WirelessHotspotMode.WIFI_P2P -> "p2p"
                    WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "ap"
                    else -> "wlan"
                }
                val found = SettingsBackupServer.addressCandidates(prefix)
                check(found.isNotEmpty())
                if (closed.get()) return@Thread
                val active = SettingsBackupServer(
                    page = assets.open("settings-backup.html").use { it.readBytes() },
                    export = { SettingsBackupCodec.export(this, packageManager.getPackageInfo(packageName, 0).versionName ?: "") },
                    import = { SettingsBackupCodec.import(this, it) },
                    report = report,
                    codePage = assets.open("settings-code.html").use { it.readBytes() },
                )
                created = active
                active.landing = if (forReport) "#report" else ""
                active.onCodeEntryClosed = { runOnUiThread { if (!closed.get()) updateAddress() } }
                active.onIdle = { runOnUiThread { if (!closed.get()) dialog.dismiss() } }
                active.onImported = { outcome -> runOnUiThread {
                    if (!closed.get() && !isFinishing && !isDestroyed) {
                        summary.text = if (outcome.invalid) getString(R.string.backup_import_invalid)
                            else getString(R.string.backup_summary, outcome.applied, outcome.skipped)
                        summary.visibility = View.VISIBLE
                        onImported(outcome)
                    }
                } }
                val listening = checkNotNull(active.start())
                val bitmap = SettingsQrCode.encode(listening.url(found.first()) + if (forReport) "#report" else "", qrSide)
                if (closed.get()) { active.stop(); bitmap.recycle(); return@Thread }
                runOnUiThread {
                    if (closed.get() || isFinishing || isDestroyed) {
                        Thread({ active.stop() }, "xplay-transfer-close").start()
                        bitmap.recycle()
                    } else {
                        server.set(active)
                        endpoint = listening; addresses = found
                        qr.setImageBitmap(bitmap); qr.visibility = View.VISIBLE
                        progress.visibility = View.GONE
                        updateAddress(); address.visibility = View.VISIBLE
                        if (found.size > 1) {
                            alternatives.text = found.drop(1).joinToString("\n") { getString(R.string.backup_other_addresses, it) }
                            alternatives.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (_: Exception) {
                created?.stop()
                runOnUiThread {
                    if (!closed.get() && !isFinishing && !isDestroyed) {
                        progress.visibility = View.GONE
                        summary.setText(R.string.backup_no_network); summary.visibility = View.VISIBLE
                    }
                }
            }
        }, "xplay-transfer-prepare").start()
    }
    return dialog
}

private const val TRANSFER_QR_DP = 240
private const val TRANSFER_LOADING_DP = 48
private const val TRANSFER_GAP_DP = 16
private const val TRANSFER_WINDOW_INSET_DP = 96
