package com.shilapi.xcertplay
import android.app.Activity
import android.content.Intent
import android.os.Bundle
/** Routes the launcher without creating a visible window. */
class AppEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = CarPlayBackgroundSession.hasSession() || CarPlayBackgroundSession.active
        val ready = existing || ConnectionEntryPolicy.configured(this)
        val target = if (ready) CarPlayHostActivity::class.java else DiPlayActivity::class.java
        val next = Intent(this, target)
        if (ready) {
            next.putExtra(ConnectionEntryPolicy.EXTRA_LAUNCHER, true)
                .putExtra(ConnectionEntryPolicy.EXTRA_START, existing || DiPlayPreferences.autoConnect(this))
            if (!existing) AirPlayPersistence.saveWirelessEnabled(this, DiPlayPreferences.autoConnectWireless(this))
        } else next.putExtra("page", "setup")
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
    }
}
