package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Explicit, cancellable handshake check. It never reads brand-specific vehicle services. */
internal fun ComponentActivity.showLocalAdbCheck(onFinished: (Result<LocalAdb.Access>?) -> Unit) {
    val completed = AtomicBoolean(false)
    val connection = AtomicReference<LocalAdb?>()
    val main = Handler(Looper.getMainLooper())
    val progress = appDialogBuilder().setTitle(R.string.check_adb_access)
        .setMessage(R.string.settings_car_adb_progress).setNegativeButton(R.string.cancel, null).create()
    val observer = LifecycleEventObserver { _, event ->
        // Authorization can briefly cover/pause the host activity, so do not cancel on pause/stop.
        if (event == Lifecycle.Event.ON_DESTROY) progress.dismiss()
    }
    lifecycle.addObserver(observer)
    progress.setOnDismissListener {
        connection.get()?.cancelPendingOperations()
        lifecycle.removeObserver(observer)
        if (completed.compareAndSet(false, true)) onFinished(null)
    }
    progress.show()
    Thread({
        val result = runCatching {
            LocalAdb(AdbKeys.load(applicationContext)).use { adb ->
                connection.set(adb)
                if (completed.get()) {
                    adb.cancelPendingOperations()
                    LocalAdb.Access.UNREACHABLE
                } else adb.connect(mayAsk = true)
            }
        }
        main.post {
            if (completed.compareAndSet(false, true)) {
                progress.dismiss()
                if (!isFinishing && !isDestroyed) {
                    onFinished(result)
                    val message = when (result.getOrNull()) {
                        LocalAdb.Access.READY -> R.string.settings_car_adb_ready
                        LocalAdb.Access.NOT_APPROVED -> R.string.settings_car_adb_not_approved
                        LocalAdb.Access.UNREACHABLE -> R.string.settings_car_adb_unreachable
                        LocalAdb.Access.UNSUPPORTED -> R.string.settings_car_adb_pairing
                        null -> R.string.adb_check_failed
                    }
                    appDialogBuilder().setTitle(R.string.check_adb_access).setMessage(message)
                        .setPositiveButton(R.string.close, null).show()
                }
            }
        }
    }, "xplay-local-adb-check").start()
}
