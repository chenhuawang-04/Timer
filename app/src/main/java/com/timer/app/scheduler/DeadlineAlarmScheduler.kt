package com.timer.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.timer.app.data.AlarmKinds
import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskRuntimeStateEntity
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import com.timer.app.domain.TimerMath

/**
 * Low-overhead alarm scheduling for reminders, countdown completion hints, and
 * time-window reconciliation.
 *
 * These alarms are wake-up hints only. Durable correctness always comes from
 * repository reconciliation on startup, foreground service refresh, receiver
 * delivery, and user-driven refresh points.
 */
class DeadlineAlarmScheduler(private val context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager = requireNotNull(appContext.getSystemService(AlarmManager::class.java)) {
        "AlarmManager service is unavailable"
    }

    fun scheduleFor(
        instances: List<TaskInstanceEntity>,
        states: List<TaskRuntimeStateEntity>,
        nowEpochMillis: Long = System.currentTimeMillis(),
        nowElapsedRealtimeMillis: Long = android.os.SystemClock.elapsedRealtime()
    ) {
        val stateById = states.associateBy { it.instanceId }
        instances.asSequence()
            .filterNot { it.archived }
            .filterNot { it.status in setOf(TaskStatuses.COMPLETED, TaskStatuses.MISSED, TaskStatuses.CANCELLED) }
            .forEach { instance ->
                val reminderAnchor = instance.preferredStartEpochMillis ?: instance.plannedStartEpochMillis
                if (instance.remindersEnabled && instance.remindAtStart && reminderAnchor != null && reminderAnchor > nowEpochMillis) {
                    schedule(instance.id, AlarmKinds.TASK_START, reminderAnchor)
                }
                if (instance.type == TaskTypes.TIME_WINDOW) {
                    val end = instance.plannedEndEpochMillis
                    if (instance.remindersEnabled && instance.remindBeforeEndMinutes != null && end != null) {
                        val preEnd = end - instance.remindBeforeEndMinutes.coerceAtLeast(0) * 60_000L
                        if (preEnd > nowEpochMillis) {
                            schedule(instance.id, AlarmKinds.WINDOW_PRE_END, preEnd)
                        }
                    }
                    if (end != null && end > nowEpochMillis) {
                        schedule(instance.id, AlarmKinds.WINDOW_DEADLINE, end)
                    }
                } else if (instance.type == TaskTypes.COUNT_DOWN) {
                    val state = stateById[instance.id]
                    val remaining = TimerMath.remainingMillis(instance, state, nowElapsedRealtimeMillis)
                    if (state?.status == TaskStatuses.RUNNING && remaining != null && remaining > 0L) {
                        schedule(instance.id, AlarmKinds.COUNTDOWN_COMPLETE, nowEpochMillis + remaining)
                    }
                }
            }
    }

    fun cancelFor(instanceId: String) {
        listOf(
            AlarmKinds.TASK_START,
            AlarmKinds.WINDOW_PRE_END,
            AlarmKinds.WINDOW_DEADLINE,
            AlarmKinds.COUNTDOWN_COMPLETE
        ).forEach { kind ->
            pendingIntentOrNull(instanceId, kind)?.let(alarmManager::cancel)
        }
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
            action = ACTION_RECONCILE_AND_REMIND
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
            action = ACTION_RECONCILE_AND_REMIND
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
        const val ACTION_RECONCILE_AND_REMIND = "com.timer.app.action.RECONCILE_AND_REMIND"
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_KIND = "kind"
    }
}
