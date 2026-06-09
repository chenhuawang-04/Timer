package com.timer.app

import android.content.Context
import com.timer.app.data.AlarmKinds
import com.timer.app.data.RoomTimerRepository
import com.timer.app.notification.TimerNotificationController
import com.timer.app.scheduler.DeadlineAlarmScheduler
import com.timer.app.service.TimerForegroundService
import com.timer.app.widget.TimerWidgetUpdater
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TimerAutomationCoordinator(
    private val context: Context,
    private val repository: RoomTimerRepository,
    private val notifications: TimerNotificationController,
    private val alarmScheduler: DeadlineAlarmScheduler,
    private val widgetUpdater: TimerWidgetUpdater
) {
    suspend fun warmUp(anchorDate: LocalDate = LocalDate.now()) = withContext(Dispatchers.IO) {
        repository.preparePlanningWindow(anchorDate)
        val result = repository.reconcileDeadlines()
        publishReconcileNotifications(result)
        scheduleAll()
        widgetUpdater.refreshAll()
        ensureForegroundIfNeeded()
    }

    suspend fun afterMutation(anchorDate: LocalDate = LocalDate.now()) = warmUp(anchorDate)

    suspend fun recoverAfterBoot(anchorDate: LocalDate = LocalDate.now()) = withContext(Dispatchers.IO) {
        repository.preparePlanningWindow(anchorDate)
        val result = repository.recoverAfterBoot()
        publishReconcileNotifications(result)
        scheduleAll()
        widgetUpdater.refreshAll()
        ensureForegroundIfNeeded()
    }

    suspend fun onAlarm(instanceId: String?, kind: String?, anchorDate: LocalDate = LocalDate.now()) = withContext(Dispatchers.IO) {
        if (instanceId != null && kind != null) {
            val instance = repository.getAllInstances().firstOrNull { it.id == instanceId }
            if (
                instance != null &&
                instance.status !in setOf(
                    com.timer.app.data.TaskStatuses.COMPLETED,
                    com.timer.app.data.TaskStatuses.CANCELLED,
                    com.timer.app.data.TaskStatuses.MISSED
                ) &&
                kind in setOf(AlarmKinds.TASK_START, AlarmKinds.WINDOW_PRE_END)
            ) {
                notifications.showReminder(instance, kind)
            }
        }
        repository.preparePlanningWindow(anchorDate)
        val result = repository.reconcileDeadlines()
        publishReconcileNotifications(result)
        scheduleAll()
        widgetUpdater.refreshAll()
        ensureForegroundIfNeeded()
    }

    private suspend fun scheduleAll() {
        alarmScheduler.scheduleFor(
            instances = repository.getAllInstances(),
            states = repository.getAllRuntimeStates()
        )
    }

    private fun publishReconcileNotifications(result: com.timer.app.data.ReconcileResult) {
        result.completedCountdowns.forEach { notifications.showCountdownCompleted(it) }
        result.missedTimeWindows.forEach { notifications.showTimeWindowMissed(it) }
    }

    private suspend fun ensureForegroundIfNeeded() {
        if (repository.getRunningTimedTasksWithStates().isNotEmpty()) {
            TimerForegroundService.start(context)
        }
    }
}
