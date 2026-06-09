package com.timer.app.domain

import com.timer.app.data.SessionModes
import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskRuntimeStateEntity
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerMathTest {
    @Test
    fun runningCountUpAddsElapsedDeltaToAccumulated() {
        val state = runtimeState(
            status = TaskStatuses.RUNNING,
            accumulatedMillis = 10_000L,
            startedAtElapsedRealtimeMillis = 100_000L
        )

        assertEquals(40_000L, TimerMath.effectiveElapsedMillis(state, 130_000L))
    }

    @Test
    fun pausedStateDoesNotAddElapsedDelta() {
        val state = runtimeState(
            status = TaskStatuses.PAUSED,
            accumulatedMillis = 10_000L,
            startedAtElapsedRealtimeMillis = null
        )

        assertEquals(10_000L, TimerMath.effectiveElapsedMillis(state, 130_000L))
    }

    @Test
    fun countdownRemainingIsClampedAtZeroAndExpiresOnlyWhenRunning() {
        val instance = countdownInstance(targetMillis = 60_000L)
        val state = runtimeState(
            instanceId = instance.id,
            status = TaskStatuses.RUNNING,
            accumulatedMillis = 50_000L,
            startedAtElapsedRealtimeMillis = 100_000L
        )

        assertEquals(0L, TimerMath.remainingMillis(instance, state, 120_000L))
        assertTrue(TimerMath.isExpiredCountdown(instance, state, 120_000L))
    }

    @Test
    fun pomodoroCountdownUsesComputedProgramDuration() {
        val instance = taskInstance(
            type = TaskTypes.COUNT_DOWN,
            sessionMode = SessionModes.POMODORO,
            pomodoroWorkMinutes = 25,
            pomodoroBreakMinutes = 5,
            pomodoroCycles = 2,
            targetDurationMillis = 3_300_000L
        )
        val state = runtimeState(
            instanceId = instance.id,
            status = TaskStatuses.RUNNING,
            accumulatedMillis = 0L,
            startedAtElapsedRealtimeMillis = 100_000L
        )

        // 25 + 5 + 25 = 55 minutes total. After 10 minutes, 45 remain.
        assertEquals(45 * 60_000L, TimerMath.remainingMillis(instance, state, 700_000L))
    }

    @Test
    fun countUpTaskNeverExpiresAsCountdown() {
        val instance = taskInstance(type = TaskTypes.COUNT_UP)
        val state = runtimeState(
            instanceId = instance.id,
            status = TaskStatuses.RUNNING,
            accumulatedMillis = Long.MAX_VALUE / 4,
            startedAtElapsedRealtimeMillis = 100_000L
        )

        assertFalse(TimerMath.isExpiredCountdown(instance, state, 120_000L))
    }

    @Test
    fun countdownSegmentIsClampedToRemainingTargetDuration() {
        val instance = countdownInstance(targetMillis = 60_000L)
        val state = runtimeState(
            instanceId = instance.id,
            status = TaskStatuses.RUNNING,
            accumulatedMillis = 50_000L
        )

        assertEquals(10_000L, TimerMath.clampCountdownSegment(instance, state, 45_000L))
    }

    private fun runtimeState(
        instanceId: String = "task",
        status: String,
        accumulatedMillis: Long,
        startedAtElapsedRealtimeMillis: Long? = 100_000L
    ) = TaskRuntimeStateEntity(
        instanceId = instanceId,
        status = status,
        accumulatedMillis = accumulatedMillis,
        startedAtEpochMillis = if (startedAtElapsedRealtimeMillis == null) null else 1_000L,
        startedAtElapsedRealtimeMillis = startedAtElapsedRealtimeMillis,
        lastPersistedAtEpochMillis = 1_000L,
        version = 1L
    )

    private fun countdownInstance(targetMillis: Long) = taskInstance(
        type = TaskTypes.COUNT_DOWN,
        targetDurationMillis = targetMillis
    )

    private fun taskInstance(
        id: String = "task",
        type: String,
        status: String = TaskStatuses.READY,
        targetDurationMillis: Long? = null,
        sessionMode: String = SessionModes.STANDARD,
        pomodoroWorkMinutes: Int? = null,
        pomodoroBreakMinutes: Int? = null,
        pomodoroCycles: Int? = null
    ) = TaskInstanceEntity(
        id = id,
        templateId = null,
        localDate = "2026-06-09",
        nameSnapshot = "Task",
        type = type,
        status = status,
        targetDurationMillis = targetDurationMillis,
        preferredStartEpochMillis = null,
        plannedStartEpochMillis = null,
        plannedEndEpochMillis = null,
        colorArgb = 0xFF0284C7,
        categoryIdSnapshot = null,
        categoryNameSnapshot = null,
        projectNameSnapshot = null,
        tagsSnapshot = null,
        noteSnapshot = null,
        priority = com.timer.app.data.TaskPriorities.MEDIUM,
        remindersEnabled = false,
        remindAtStart = false,
        remindBeforeEndMinutes = null,
        remindAtDeadline = false,
        countTowardGoals = true,
        sessionMode = sessionMode,
        pomodoroWorkMinutes = pomodoroWorkMinutes,
        pomodoroBreakMinutes = pomodoroBreakMinutes,
        pomodoroCycles = pomodoroCycles,
        sortOrder = 0,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        completedAtEpochMillis = null,
        missedAtEpochMillis = null,
        cancelledAtEpochMillis = null,
        completionSource = null,
        missSource = null,
        resultNote = null,
        archived = false,
        archivedAtEpochMillis = null
    )
}
