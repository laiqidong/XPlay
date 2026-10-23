// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.adb.LocalAdbDevicePolicy

/** Independent recovery journals: disconnecting one profile never claims ownership of the other. */
internal object P7HfpSessionGuard {
    private val phone = P7ProfileSessionGuard(16, "HFP", "p7_hfp_session", true)
    private val music = P7ProfileSessionGuard(11, "A2DP", "p7_a2dp_session", false)
    fun enabled(ctx: Context) = phone.enabled(ctx)
    fun saveEnabled(ctx: Context, value: Boolean) = phone.saveEnabled(ctx, value)
    fun musicEnabled(ctx: Context) = music.enabled(ctx)
    fun saveMusicEnabled(ctx: Context, value: Boolean) = music.saveEnabled(ctx, value)
    fun recover(ctx: Context) { phone.recover(ctx); music.recover(ctx) }
    fun connected(ctx: Context) { phone.connected(ctx); music.connected(ctx) }
    fun disconnected() { phone.disconnected(); music.disconnected() }
}

/** Owns only connections disconnected by XPlay. Never changes pairing or profile priority. */
private class P7ProfileSessionGuard(
    private val profileId: Int,
    private val label: String,
    private val preferenceName: String,
    private val handsFree: Boolean,
) {
    private companion object {
        const val TAG = "XPlay-BluetoothAudio"
        const val RETRY_MS = 5_000L
    }
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var proxy: BluetoothProfile? = null
    private var binding = false
    private var active = false
    private var sessionEnabled = false
    private var bindingGeneration = 0
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun saveEnabled(ctx: Context, value: Boolean) { prefs(ctx).edit().putBoolean("enabled", value).apply() }

    fun recover(ctx: Context) = update(ctx, CarPlayBackgroundSession.active)
    fun connected(ctx: Context) = update(ctx, true)
    fun disconnected() { context?.let { update(it, false) } }

    private val tick = Runnable { reconcile() }
    private fun update(ctx: Context, connected: Boolean) {
        handler.post {
            if (!LocalAdbDevicePolicy.isXpengP7()) return@post
            context = ctx.applicationContext
            if (connected && !active) sessionEnabled = enabled(ctx)
            active = connected
            handler.removeCallbacks(tick)
            // Allow a reconnect to retain the same profile lease; never tie this to Activity visibility.
            handler.postDelayed(tick, 2_000L)
        }
    }

    private fun reconcile() {
        val ctx = context ?: return
        val suppress = active && sessionEnabled && !AirPlayPersistence.loadCarBluetoothAudio(ctx)
        val saved = prefs(ctx).getString("restore_device", null)
        if (!suppress && saved == null) { close(); return }
        try {
            val bt = adapter ?: BluetoothAdapter.getDefaultAdapter()?.also { adapter = it } ?: return
            if (!bt.isEnabled) { handler.postDelayed(tick, RETRY_MS); return }
            val current = proxy
            if (current == null) {
                if (!binding) {
                    binding = true
                    val generation = ++bindingGeneration
                    binding = bt.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                            handler.post {
                                if (generation != bindingGeneration) { bt.closeProfileProxy(profile, service); return@post }
                                binding = false
                                proxy = service
                                reconcile()
                            }
                        }
                        override fun onServiceDisconnected(profile: Int) {
                            handler.post { if (generation == bindingGeneration) { proxy = null; binding = false } }
                        }
                    }, profileId)
                    handler.postDelayed({ if (binding && generation == bindingGeneration) { binding = false; bindingGeneration++ } }, RETRY_MS)
                }
            } else if (suppress) {
                val devices = current.connectedDevices
                val selected = DiPlayPreferences.phoneAddress(ctx)
                val device = if (saved != null) devices.firstOrNull { it.address == saved }
                    else if (selected != null) devices.firstOrNull { it.address.equals(selected, true) }
                    else devices.singleOrNull()
                if (device != null) {
                    // HFP is left connected while its call audio is active; A2DP has no call-audio state.
                    val canDisconnect = !handsFree || current.javaClass
                        .getMethod("getAudioState", BluetoothDevice::class.java).invoke(current, device) == 0
                    if (canDisconnect) {
                        // Persist recovery before the disconnect request; addresses never enter logs/reports.
                        if (prefs(ctx).edit().putString("restore_device", device.address).commit()) {
                            val accepted = current.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                                .invoke(current, device) == true
                            Log.i(TAG, "$label disconnect accepted=$accepted")
                            if (!accepted && saved == null) prefs(ctx).edit().remove("restore_device").commit()
                        }
                    }
                }
            } else if (saved != null) {
                val device = bt.getRemoteDevice(saved)
                if (current.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    prefs(ctx).edit().remove("restore_device").commit()
                    Log.i(TAG, "$label restored")
                    close()
                    return
                }
                if (bt.bondedDevices.any { it.address == saved }) {
                    val accepted = current.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(current, device)
                    Log.i(TAG, "$label restore accepted=$accepted")
                } else {
                    prefs(ctx).edit().remove("restore_device").commit()
                    close()
                    return
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "$label operation unavailable: ${error.javaClass.simpleName}")
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, RETRY_MS)
    }

    private fun close() {
        handler.removeCallbacks(tick)
        bindingGeneration++
        binding = false
        proxy?.let { runCatching { adapter?.closeProfileProxy(profileId, it) } }
        proxy = null
        adapter = null
    }
}
