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
import com.timer.app.data.CompletedTaskNotification
import com.timer.app.data.MissedTaskNotification
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import com.timer.app.data.TimedTaskWithState
import com.timer.app.domain.DurationFormatter
import com.timer.app.domain.TimerMath
import com.timer.app.service.TimerForegroundService

class TimerNotificationController(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val foreground = NotificationChannel(
            CHANNEL_RUNNING,
            "Running timers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active long-running timer tasks"
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "Task alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for countdown completion and missed time-window tasks"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(foreground)
        manager.createNotificationChannel(alerts)
    }

    fun buildForegroundNotification(running: List<TimedTaskWithState>): Notification {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val primary = running.firstOrNull()
        val title = when {
            running.isEmpty() -> "Timer standby"
            running.size == 1 -> primary?.instance?.nameSnapshot ?: "Timer running"
            else -> "${running.size} timers running"
        }
        val text = if (primary == null) {
            "Synchronizing local task state..."
        } else {
            val display = TimerMath.displayMillis(primary.instance, primary.state, nowElapsed)
            val prefix = if (primary.instance.type == TaskTypes.COUNT_DOWN) "Remaining" else "Elapsed"
            "$prefix ${DurationFormatter.clock(display)}"
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
                "Pause",
                serviceActionPendingIntent(TimerForegroundService.ACTION_PAUSE_INSTANCE, primary.instance.id, 10)
            )
            builder.addAction(
                0,
                "Cancel",
                serviceActionPendingIntent(TimerForegroundService.ACTION_CANCEL_INSTANCE, primary.instance.id, 11)
            )
        }
        return builder.build()
    }

    fun buildBootstrapNotification(): Notification = NotificationCompat.Builder(context, CHANNEL_RUNNING)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Timer is restoring")
        .setContentText("Synchronizing local task state...")
        .setContentIntent(activityPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    fun showCountdownCompleted(completed: CompletedTaskNotification) {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Countdown complete")
            .setContentText("${completed.taskName} is complete")
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
            .setContentTitle("Task missed")
            .setContentText("${missed.taskName} was not completed in time")
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
        const val CHANNEL_ALERTS = "timer_alerts"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }
}
