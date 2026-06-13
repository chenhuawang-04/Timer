package com.timer.app

import android.content.Context
import com.timer.app.data.AppPreferencesRepository
import com.timer.app.data.RoomTimerRepository
import com.timer.app.data.TaskRuntimeStateEntity
import com.timer.app.domain.BreakReminderMath
import com.timer.app.domain.InterruptedTask
import com.timer.app.domain.RecoveryDetector
import com.timer.app.domain.TimerClock
import kotlinx.coroutines.flow.first

data class BreakReminderEvent(
    val instanceId: String,
    val taskName: String,
    val elapsedMinutes: Int
)

data class RecoveryEvent(
    val interruptedTasks: List<InterruptedTask>
)

class TimerInterruptionCoordinator(
    private val context: Context,
    private val repository: RoomTimerRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val clock: TimerClock
) {
    /**
     * Check for tasks that need a break reminder
     */
    suspend fun checkBreakReminders(): List<BreakReminderEvent> {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.breakReminderEnabled) return emptyList()

        val nowEpoch = clock.nowEpochMillis()
        val nowElapsed = clock.elapsedRealtimeMillis()
        val running = repository.getRunningTimedTasksWithStates()

        val reminders = mutableListOf<BreakReminderEvent>()

        running.forEach { taskWithState ->
            if (BreakReminderMath.shouldShowBreakReminder(
                    state = taskWithState.state,
                    nowEpochMillis = nowEpoch,
                    intervalMinutes = prefs.breakReminderIntervalMinutes,
                    nowElapsedRealtimeMillis = nowElapsed
                )) {
                reminders.add(
                    BreakReminderEvent(
                        instanceId = taskWithState.instance.id,
                        taskName = taskWithState.instance.nameSnapshot,
                        elapsedMinutes = prefs.breakReminderIntervalMinutes
                    )
                )
            }
        }

        return reminders
    }

    /**
     * Check for tasks that should auto-pause due to no response to break reminder
     */
    suspend fun checkAutoPause(): List<String> {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.breakReminderEnabled) return emptyList()

        val nowEpoch = clock.nowEpochMillis()
        val running = repository.getRunningTimedTasksWithStates()

        val toPause = mutableListOf<String>()

        running.forEach { taskWithState ->
            if (BreakReminderMath.shouldAutoPause(
                    state = taskWithState.state,
                    nowEpochMillis = nowEpoch,
                    timeoutMinutes = prefs.breakDurationMinutes
                )) {
                toPause.add(taskWithState.instance.id)
            }
        }

        return toPause
    }

    /**
     * Mark that a break reminder was shown for a task
     */
    suspend fun markBreakReminderShown(instanceId: String) {
        val nowEpoch = clock.nowEpochMillis()
        repository.updateBreakReminderTimestamp(instanceId, nowEpoch)
    }

    /**
     * Set a task to take a break for the specified duration
     */
    suspend fun takeBreak(instanceId: String, durationMinutes: Int) {
        val nowEpoch = clock.nowEpochMillis()
        val breakUntil = nowEpoch + (durationMinutes * 60_000L)
        repository.pauseInstance(instanceId)
        repository.updateBreakUntil(instanceId, breakUntil)
    }

    /**
     * Detect interrupted tasks on app startup
     */
    suspend fun detectInterruptedTasks(): RecoveryEvent {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.autoRecoveryEnabled) {
            return RecoveryEvent(emptyList())
        }

        val nowEpoch = clock.nowEpochMillis()
        val nowElapsed = clock.elapsedRealtimeMillis()
        val instances = repository.getAllInstances()
        val states = repository.getAllRuntimeStates()

        val interrupted = RecoveryDetector.detectInterruptedTasks(
            instances = instances,
            states = states,
            nowEpochMillis = nowEpoch,
            nowElapsedRealtimeMillis = nowElapsed,
            thresholdSeconds = 30
        )

        return RecoveryEvent(interrupted)
    }

    /**
     * Apply recovery by adjusting the accumulated time and updating instance timestamp
     */
    suspend fun applyRecovery(instanceId: String, newAccumulatedMillis: Long) {
        repository.applyRecoveryAdjustment(instanceId, newAccumulatedMillis)
    }
}
