package com.shilapi.xcertplay.update

import android.content.Context
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

internal object BackgroundUpdatePolicy {
    fun connectionBusy(hasSession: Boolean, active: Boolean): Boolean = hasSession && !active
}

/** Keep the class loadable so already-persisted jobs finish without making any network request. */
internal class BackgroundUpdateWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}

internal object BackgroundUpdateScheduler {
    private const val PERIODIC_WORK = "diplay-periodic-update-check"
    private const val OPEN_WORK = "diplay-open-update-check"

    /** Also used by old callers after an upstream merge: never schedule DiPlay update requests. */
    fun schedule(context: Context) = disable(context)

    fun disable(context: Context) {
        UpdateAvailability.saveBackgroundChecksEnabled(context, false)
        UpdateAvailability.clear(context)
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(PERIODIC_WORK)
        manager.cancelUniqueWork(OPEN_WORK)
    }
}
