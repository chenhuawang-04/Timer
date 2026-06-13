package com.timer.app.domain

import com.timer.app.data.TaskRuntimeStateEntity

object BreakReminderMath {
    /**
     * Check if a break reminder should be shown based on accumulated time
     */
    fun shouldShowBreakReminder(
        state: TaskRuntimeStateEntity,
        nowEpochMillis: Long,
        intervalMinutes: Int,
        nowElapsedRealtimeMillis: Long
    ): Boolean {
        if (state.status != "RUNNING") return false
        if (state.breakUntilEpochMillis != null && nowEpochMillis < state.breakUntilEpochMillis) {
            // Currently in break period
            return false
        }

        val totalElapsed = TimerMath.effectiveElapsedMillis(state, nowElapsedRealtimeMillis)

        val intervalMillis = intervalMinutes * 60_000L
        val lastReminder = state.lastBreakReminderAtEpochMillis ?: 0L

        // Show reminder if we've passed the next interval threshold
        // AND either this is the first reminder (lastReminder == 0) with enough elapsed time
        // OR enough time has passed since the last reminder
        if (totalElapsed < intervalMillis) return false

        if (lastReminder == 0L) {
            // First reminder: show only if we've accumulated the full interval
            return totalElapsed >= intervalMillis
        } else {
            // Subsequent reminders: show if interval has passed since last reminder
            return (nowEpochMillis - lastReminder) >= intervalMillis
        }
    }

    /**
     * Check if the break period has expired and task should auto-pause
     */
    fun shouldAutoPause(
        state: TaskRuntimeStateEntity,
        nowEpochMillis: Long,
        timeoutMinutes: Int
    ): Boolean {
        // Only auto-pause if task is still running and a break reminder was shown
        if (state.status != "RUNNING") return false
        val breakReminder = state.lastBreakReminderAtEpochMillis ?: return false

        // Don't auto-pause if user already set a break period
        if (state.breakUntilEpochMillis != null) return false

        val timeoutMillis = timeoutMinutes * 60_000L
        return (nowEpochMillis - breakReminder) >= timeoutMillis
    }

    /**
     * Calculate if still in break period
     */
    fun isInBreakPeriod(
        state: TaskRuntimeStateEntity,
        nowEpochMillis: Long
    ): Boolean {
        val breakUntil = state.breakUntilEpochMillis ?: return false
        return nowEpochMillis < breakUntil
    }
}
