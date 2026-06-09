package com.timer.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.timer.app.MainActivity
import com.timer.app.R
import com.timer.app.data.TaskStatuses
import com.timer.app.domain.DurationFormatter
import com.timer.app.domain.PomodoroMath
import com.timer.app.domain.StatsCalculator
import com.timer.app.domain.TimerMath
import com.timer.app.timerApplication
import java.time.Instant
import java.time.ZoneId

class TimerWidgetUpdater(private val context: Context) {
    private val appContext = context.applicationContext
    private val appWidgetManager = AppWidgetManager.getInstance(appContext)

    suspend fun refreshAll() {
        updateTodaySummaryWidget()
        updateActiveTimerWidget()
    }

    private suspend fun updateTodaySummaryWidget() {
        val widgetIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, TodaySummaryWidgetProvider::class.java))
        if (widgetIds.isEmpty()) return
        val repository = appContext.timerApplication().container.repository
        val instances = repository.getAllInstances()
        val states = repository.getAllRuntimeStates()
        val sessions = repository.getAllSessions()
        val nowEpoch = System.currentTimeMillis()
        val stats = StatsCalculator.calculate(
            instances = instances,
            states = states,
            sessions = sessions,
            nowEpochMillis = nowEpoch,
            nowElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            zoneId = ZoneId.systemDefault()
        )
        val today = Instant.ofEpochMilli(nowEpoch).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val nextTask = instances
            .filter { it.localDate == today && it.status !in setOf(TaskStatuses.COMPLETED, TaskStatuses.CANCELLED, TaskStatuses.MISSED) }
            .sortedBy { it.plannedStartEpochMillis ?: it.preferredStartEpochMillis ?: Long.MAX_VALUE }
            .firstOrNull()
        val title = appContext.getString(R.string.widget_today_title)
        val summary = appContext.getString(
            R.string.widget_today_summary,
            stats.completedTodayCount,
            stats.plannedTodayCount,
            DurationFormatter.compact(stats.trackedTodayMillis)
        )
        val next = nextTask?.nameSnapshot ?: appContext.getString(R.string.widget_no_next_task)

        widgetIds.forEach { widgetId ->
            val views = RemoteViews(appContext.packageName, R.layout.widget_today_summary)
            views.setTextViewText(R.id.widgetTitle, title)
            views.setTextViewText(R.id.widgetSummary, summary)
            views.setTextViewText(R.id.widgetSecondary, appContext.getString(R.string.widget_next_task, next))
            views.setOnClickPendingIntent(R.id.widgetRoot, activityPendingIntent())
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private suspend fun updateActiveTimerWidget() {
        val widgetIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, ActiveTimerWidgetProvider::class.java))
        if (widgetIds.isEmpty()) return
        val repository = appContext.timerApplication().container.repository
        val running = repository.getRunningTimedTasksWithStates()
        val primary = running.firstOrNull()
        widgetIds.forEach { widgetId ->
            val views = RemoteViews(appContext.packageName, R.layout.widget_active_timer)
            views.setOnClickPendingIntent(R.id.widgetRoot, activityPendingIntent())
            if (primary == null) {
                views.setTextViewText(R.id.widgetActiveTitle, appContext.getString(R.string.widget_focus_idle_title))
                views.setTextViewText(R.id.widgetActiveBody, appContext.getString(R.string.widget_focus_idle_body))
            } else {
                val nowElapsed = SystemClock.elapsedRealtime()
                val display = TimerMath.displayMillis(primary.instance, primary.state, nowElapsed)
                val phase = PomodoroMath.phaseFor(primary.instance, primary.state, nowElapsed)
                views.setTextViewText(R.id.widgetActiveTitle, primary.instance.nameSnapshot)
                views.setTextViewText(
                    R.id.widgetActiveBody,
                    if (phase == null) {
                        DurationFormatter.clock(display)
                    } else {
                        appContext.getString(
                            R.string.widget_focus_phase,
                            phase.cycleNumber,
                            phase.totalCycles,
                            if (phase.phaseType == com.timer.app.domain.PomodoroPhaseTypes.WORK) {
                                appContext.getString(R.string.pomodoro_phase_work)
                            } else {
                                appContext.getString(R.string.pomodoro_phase_break)
                            },
                            DurationFormatter.clock(phase.phaseRemainingMillis)
                        )
                    }
                )
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun activityPendingIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            301,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
