package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.shilapi.xcertplay.host.R

/** Short picker detent. Uses UI sonification routing without requesting audio focus. */
internal class P7ClimateFeedback(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    @Volatile private var ready = false
    private var lastTick = 0L
    private val sound: Int
    init {
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        sound = pool.load(context, R.raw.climate_tick, 1)
    }
    fun tick() {
        val now = SystemClock.uptimeMillis()
        // Rapid sweeps should feel like detents rather than a continuous buzz.
        if (!ready || now - lastTick < 35L) return
        lastTick = now
        pool.play(sound, .35f, .35f, 1, 0, 1f)
    }
    fun close() { ready = false; pool.release() }
}
