package com.shilapi.xcertplay
import android.app.Activity
import android.os.Bundle
/** Routes the launcher without creating a visible window. */
class AppEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(ConnectionEntryPolicy.launchIntent(this))
        finish()
    }
}
