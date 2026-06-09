package com.timer.app.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.timer.app.MainActivity
import com.timer.app.R
import com.timer.app.data.AlarmKinds
import com.timer.app.data.CompletedTaskNotification
import com.timer.app.data.MissedTaskNotification
import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import com.timer.app.data.TimedTaskWithState
import com.timer.app.domain.DurationFormatter
import com.timer.app.domain.PomodoroMath
import com.timer.app.domain.PomodoroPhaseTypes
import com.timer.app.domain.TimerMath
import com.timer.app.service.TimerForegroundService

class TimerNotificationController(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val foreground = NotificationChannel(
            CHANNEL_RUNNING,
            context.getString(R.string.notification_channel_running),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_running_description)
            setShowBadge(false)
        }
        val reminders = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.notification_channel_reminders),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_reminders_description)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_alerts_description)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(foreground)
        manager.createNotificationChannel(reminders)
        manager.createNotificationChannel(alerts)
    }

    fun buildForegroundNotification(running: List<TimedTaskWithState>): Notification {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val primary = running.firstOrNull()
        val title = when {
            running.isEmpty() -> context.getString(R.string.notification_foreground_title_standby)
            running.size == 1 -> primary?.instance?.nameSnapshot ?: context.getString(R.string.notification_foreground_title_single_fallback)
            else -> context.getString(R.string.notification_foreground_title_multi, running.size)
        }
        val text = when {
            primary == null -> context.getString(R.string.notification_foreground_text_sync)
            PomodoroMath.isPomodoro(primary.instance) -> {
                val phase = PomodoroMath.phaseFor(primary.instance, primary.state, nowElapsed)
                if (phase == null || phase.phaseType == PomodoroPhaseTypes.DONE) {
                    context.getString(R.string.notification_countdown_complete_text, primary.instance.nameSnapshot)
                } else {
                    context.getString(
                        R.string.notification_pomodoro_phase,
                        phase.cycleNumber,
                        phase.totalCycles,
                        if (phase.phaseType == PomodoroPhaseTypes.WORK) {
                            context.getString(R.string.pomodoro_phase_work)
                        } else {
                            context.getString(R.string.pomodoro_phase_break)
                        },
                        DurationFormatter.clock(phase.phaseRemainingMillis)
                    )
                }
            }
            else -> {
                val display = TimerMath.displayMillis(primary.instance, primary.state, nowElapsed)
                if (primary.instance.type == TaskTypes.COUNT_DOWN) {
                    context.getString(R.string.notification_foreground_prefix_remaining, DurationFormatter.clock(display))
                } else {
                    context.getString(R.string.notification_foreground_prefix_elapsed, DurationFormatter.clock(display))
                }
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(activityPendingIntent())
            .setOngoing(running.isNotEmpty())
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (primary != null && primary.state.status == TaskStatuses.RUNNING) {
            builder.addAction(
                0,
                context.getString(R.string.action_pause),
                serviceActionPendingIntent(TimerForegroundService.ACTION_PAUSE_INSTANCE, primary.instance.id, 10)
            )
            val secondaryAction = if (primary.instance.type == TaskTypes.COUNT_UP) {
                TimerForegroundService.ACTION_COMPLETE_INSTANCE
            } else {
                TimerForegroundService.ACTION_CANCEL_INSTANCE
            }
            val secondaryLabel = if (primary.instance.type == TaskTypes.COUNT_UP) {
                context.getString(R.string.action_complete)
            } else {
                context.getString(R.string.action_cancel)
            }
            builder.addAction(
                0,
                secondaryLabel,
                serviceActionPendingIntent(secondaryAction, primary.instance.id, 11)
            )
        }
        return builder.build()
    }

    fun buildBootstrapNotification(): Notification = NotificationCompat.Builder(context, CHANNEL_RUNNING)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(context.getString(R.string.notification_bootstrap_title))
        .setContentText(context.getString(R.string.notification_foreground_text_sync))
        .setContentIntent(activityPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    fun showReminder(instance: TaskInstanceEntity, kind: String) {
        if (!canPostNotifications()) return
        val text = when (kind) {
            AlarmKinds.TASK_START -> context.getString(R.string.notification_reminder_start_text, instance.nameSnapshot)
            AlarmKinds.WINDOW_PRE_END -> context.getString(R.string.notification_reminder_window_end_text, instance.nameSnapshot)
            else -> context.getString(R.string.notification_reminder_generic_text, instance.nameSnapshot)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_reminder_title))
            .setContentText(text)
            .setContentIntent(activityPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify("${instance.id}:$kind".hashCode(), notification)
    }

    fun showCountdownCompleted(completed: CompletedTaskNotification) {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_countdown_complete_title))
            .setContentText(context.getString(R.string.notification_countdown_complete_text, completed.taskName))
            .setContentIntent(activityPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(completed.instanceId.hashCode(), notification)
    }

    fun showTimeWindowMissed(missed: MissedTaskNotification) {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_task_missed_title))
            .setContentText(context.getString(R.string.notification_task_missed_text, missed.taskName))
            .setContentIntent(activityPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(missed.instanceId.hashCode(), notification)
    }

    fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun activityPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceActionPendingIntent(action: String, instanceId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, TimerForegroundService::class.java).apply {
            this.action = action
            putExtra(TimerForegroundService.EXTRA_INSTANCE_ID, instanceId)
        }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_RUNNING = "timer_running"
        const val CHANNEL_REMINDERS = "timer_reminders"
        const val CHANNEL_ALERTS = "timer_alerts"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }
}
