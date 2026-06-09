package com.timer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timer.app.service.TimerForegroundService
import com.timer.app.timerApplication
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.timerApplication()
        app.applicationScope.launch {
            try {
                val result = app.container.repository.recoverAfterBoot()
                result.completedCountdowns.forEach { app.container.notificationController.showCountdownCompleted(it) }
                result.missedTimeWindows.forEach { app.container.notificationController.showTimeWindowMissed(it) }
                app.container.deadlineAlarmScheduler.scheduleFor(app.container.repository.getAllInstances())
                if (app.container.repository.getRunningTimedTasksWithStates().isNotEmpty()) {
                    TimerForegroundService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
