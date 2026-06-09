package com.timer.app.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

class CloudSyncScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val jobScheduler: JobScheduler =
        requireNotNull(appContext.getSystemService(JobScheduler::class.java))

    fun refresh(configuration: CloudSyncConfiguration, hasUsableToken: Boolean) {
        val normalized = configuration.normalized()
        if (!normalized.autoSyncEnabled || !normalized.isComplete(hasUsableToken)) {
            cancel()
            return
        }
        val jobInfo = JobInfo.Builder(
            CloudSyncDefaults.JOB_ID,
            ComponentName(appContext, CloudSyncJobService::class.java)
        )
            .setRequiredNetworkType(
                if (normalized.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
            )
            .setPersisted(true)
            .setPeriodic(
                CloudSyncDefaults.PERIODIC_INTERVAL_MILLIS,
                CloudSyncDefaults.PERIODIC_FLEX_MILLIS
            )
            .build()
        jobScheduler.schedule(jobInfo)
    }

    fun cancel() {
        jobScheduler.cancel(CloudSyncDefaults.JOB_ID)
    }
}

