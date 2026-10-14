package com.shilapi.xcertplay

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.shilapi.xcertplay.host.R

/** Reads actual grants; never assumes a missing prompt means permission was granted. */
internal object RestrictedCarUi {
    fun manualSettings(context: Context, title: Int = R.string.settings_car_manual_title, help: Int = R.string.settings_car_manual_help) {
        context.appDialogBuilder().setTitle(title)
            .setMessage(help)
            .setPositiveButton(R.string.close, null).show()
    }

    fun permissions(context: Context) {
        val rows = mutableListOf<String>()
        fun status(label: Int, permissions: List<String>, required: Boolean = true) {
            val value = when {
                !required -> R.string.settings_car_not_required
                permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } -> R.string.settings_car_granted
                else -> R.string.settings_car_missing
            }
            rows += "${context.getString(label)} · ${context.getString(value)}"
        }
        fun special(label: Int, allowed: () -> Boolean) {
            val state = runCatching { if (allowed()) R.string.settings_car_granted else R.string.settings_car_missing }
                .getOrDefault(R.string.settings_car_unknown)
            rows += "${context.getString(label)} · ${context.getString(state)}"
        }
        status(R.string.settings_car_bluetooth,
            if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN))
        if (Build.VERSION.SDK_INT >= 33) status(R.string.wireless_permissions, listOf(Manifest.permission.NEARBY_WIFI_DEVICES))
        status(R.string.location, listOf(Manifest.permission.ACCESS_FINE_LOCATION))
        status(R.string.settings_car_microphone, listOf(Manifest.permission.RECORD_AUDIO))
        status(R.string.settings_car_storage, listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), Build.VERSION.SDK_INT <= 28)
        status(R.string.settings_car_notifications, listOf(Manifest.permission.POST_NOTIFICATIONS), Build.VERSION.SDK_INT >= 33)
        special(R.string.settings_car_overlay) { Settings.canDrawOverlays(context) }
        special(R.string.settings_car_accessibility) { UsbAutoConfirmService.isEnabled(context) }
        special(R.string.settings_car_usage) {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }
        context.appDialogBuilder().setTitle(R.string.settings_car_permission_status)
            .setMessage(rows.joinToString("\n") + "\n\n" + context.getString(R.string.settings_car_manual_help))
            .setPositiveButton(R.string.close, null).show()
    }
}
