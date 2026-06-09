package com.timer.app.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timer.app.service.TimerForegroundService
import com.timer.app.timerApplication
import kotlinx.coroutines.launch

class DeadlineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DeadlineAlarmScheduler.ACTION_RECONCILE_DEADLINES) return
        val pending = goAsync()
        val app = context.timerApplication()
        app.applicationScope.launch {
            try {
                val repository = app.container.repository
                val result = repository.reconcileDeadlines()
                result.completedCountdowns.forEach { app.container.notificationController.showCountdownCompleted(it) }
                result.missedTimeWindows.forEach { app.container.notificationController.showTimeWindowMissed(it) }
                app.container.deadlineAlarmScheduler.scheduleFor(repository.getAllInstances())
                if (repository.getRunningTimedTasksWithStates().isNotEmpty()) {
                    TimerForegroundService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
