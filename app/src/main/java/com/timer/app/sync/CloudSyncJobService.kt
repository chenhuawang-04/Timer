package com.timer.app.sync

import android.app.job.JobParameters
import android.app.job.JobService
import com.timer.app.timerApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CloudSyncJobService : JobService() {
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext.timerApplication()
        runningJob = app.applicationScope.launch {
            var shouldReschedule = false
            try {
                app.container.cloudSyncCoordinator.runScheduledSync()
            } catch (_: Throwable) {
                shouldReschedule = true
            } finally {
                jobFinished(params, shouldReschedule)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        return true
    }
}

