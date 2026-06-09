package com.timer.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes

/**
 * Low-overhead deadline scheduling for time-window task reconciliation.
 *
 * This scheduler is deliberately a wake-up hint, not the source of truth:
 * Room state is reconciled transactionally by RoomTimerRepository whenever the
 * app starts, resumes through the dashboard ViewModel, the foreground service
 * ticks, boot/package replacement is received, or one of these alarms fires.
 *
 * We avoid exact-alarm permissions at this stage. setAndAllowWhileIdle gives
 * Android permission to batch delivery for battery health, while the eventual
 * reconciliation pass still guarantees durable state correctness.
 */
class DeadlineAlarmScheduler(private val context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager = requireNotNull(appContext.getSystemService(AlarmManager::class.java)) {
        "AlarmManager service is unavailable"
    }

    fun scheduleFor(instances: List<TaskInstanceEntity>, nowEpochMillis: Long = System.currentTimeMillis()) {
        instances
            .asSequence()
            .filter { it.type == TaskTypes.TIME_WINDOW }
            .filterNot { it.archived }
            .filter { it.status == TaskStatuses.PLANNED || it.status == TaskStatuses.READY }
            .forEach { instance ->
                val start = instance.plannedStartEpochMillis
                val end = instance.plannedEndEpochMillis
                if (start != null && start > nowEpochMillis) {
                    schedule(instance.id, KIND_WINDOW_START, start)
                }
                if (end != null && end > nowEpochMillis) {
                    schedule(instance.id, KIND_WINDOW_END, end)
                }
            }
    }

    fun cancelFor(instanceId: String) {
        pendingIntentOrNull(instanceId, KIND_WINDOW_START)?.let(alarmManager::cancel)
        pendingIntentOrNull(instanceId, KIND_WINDOW_END)?.let(alarmManager::cancel)
    }

    private fun schedule(instanceId: String, kind: String, triggerAtEpochMillis: Long) {
        val intent = pendingIntent(instanceId, kind, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, intent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, intent)
        }
    }

    private fun pendingIntent(instanceId: String, kind: String, extraFlags: Int): PendingIntent {
        val intent = Intent(appContext, DeadlineAlarmReceiver::class.java).apply {
            action = ACTION_RECONCILE_DEADLINES
            putExtra(EXTRA_INSTANCE_ID, instanceId)
            putExtra(EXTRA_KIND, kind)
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode(instanceId, kind),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or extraFlags
        )
    }

    private fun pendingIntentOrNull(instanceId: String, kind: String): PendingIntent? {
        val intent = Intent(appContext, DeadlineAlarmReceiver::class.java).apply {
            action = ACTION_RECONCILE_DEADLINES
            putExtra(EXTRA_INSTANCE_ID, instanceId)
            putExtra(EXTRA_KIND, kind)
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode(instanceId, kind),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(instanceId: String, kind: String): Int = "$instanceId:$kind".hashCode()

    companion object {
        const val ACTION_RECONCILE_DEADLINES = "com.timer.app.action.RECONCILE_DEADLINES"
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_KIND = "kind"
        private const val KIND_WINDOW_START = "window_start"
        private const val KIND_WINDOW_END = "window_end"
    }
}
