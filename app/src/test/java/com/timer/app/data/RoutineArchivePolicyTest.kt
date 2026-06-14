package com.timer.app.data

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineArchivePolicyTest {
    private val today = LocalDate.of(2026, 6, 9)

    @Test
    fun shouldCancelTodayAndFutureUnstartedRoutinePlans() {
        val todayPlan = instance(localDate = "2026-06-09")
        val futurePlan = instance(localDate = "2026-06-10", status = TaskStatuses.PLANNED)

        assertTrue(
            RoutineArchivePolicy.shouldCancelPendingInstance(
                instance = todayPlan,
                state = state(todayPlan.id),
                hasSessions = false,
                today = today
            )
        )
        assertTrue(
            RoutineArchivePolicy.shouldCancelPendingInstance(
                instance = futurePlan,
                state = null,
                hasSessions = false,
                today = today
            )
        )
    }

    @Test
    fun shouldPreservePastWorkedTerminalAndUserTouchedRoutineInstances() {
        val pastPlan = instance(id = "past", localDate = "2026-06-08")
        val completed = instance(id = "completed", status = TaskStatuses.COMPLETED)
        val runningStateMismatch = instance(id = "running_state")
        val noted = instance(id = "noted", resultNote = "keep this context")
        val worked = instance(id = "worked")

        assertFalse(
            RoutineArchivePolicy.shouldCancelPendingInstance(
                instance = pastPlan,
                state = state(pastPlan.id),
                hasSessions = false,
                today = today
            )
        )
        assertFalse(
            RoutineArchivePolicy.shouldCancelPendingInstance(
                instance = completed,
                state = state(completed.id, status = TaskStatuses.COMPLETED),
                hasSessions = false,
                today = today
            )
        )
        assertFalse(
            RoutineArchivePolicy.shouldCancelPendingInstance(
                instance = runningStateMismatch,
                state = state(runningStateMismatch.id, status = TaskStatuses.RUNNING),
                hasSessions = false,
                today = today
            )
        )
        assertFalse(
            RoutineArchivePolicy.shouldCancelPendingInstance(
                instance = noted,
                state = state(noted.id),
                hasSessions = false,
                today = today
            )
        )
        assertFalse(
            RoutineArchivePolicy.shouldCancelPendingInstance(
                instance = worked,
                state = state(worked.id, accumulatedMillis = 60_000L),
                hasSessions = true,
                today = today
            )
        )
    }

    private fun instance(
        id: String = "instance",
        templateId: String? = "template",
        localDate: String = "2026-06-09",
        status: String = TaskStatuses.READY,
        archived: Boolean = false,
        resultNote: String? = null
    ) = TaskInstanceEntity(
        id = id,
        templateId = templateId,
        localDate = localDate,
        nameSnapshot = "Routine plan",
        type = TaskTypes.COUNT_UP,
        status = status,
        targetDurationMillis = null,
        preferredStartEpochMillis = null,
        plannedStartEpochMillis = null,
        plannedEndEpochMillis = null,
        colorArgb = 0xFF2563EB,
        categoryIdSnapshot = null,
        categoryNameSnapshot = null,
        projectNameSnapshot = null,
        tagsSnapshot = null,
        noteSnapshot = null,
        priority = TaskPriorities.MEDIUM,
        remindersEnabled = false,
        remindAtStart = false,
        remindBeforeEndMinutes = null,
        remindAtDeadline = false,
        countTowardGoals = true,
        sessionMode = SessionModes.STANDARD,
        pomodoroWorkMinutes = null,
        pomodoroBreakMinutes = null,
        pomodoroCycles = null,
        sortOrder = 0,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        completedAtEpochMillis = null,
        missedAtEpochMillis = null,
        cancelledAtEpochMillis = null,
        completionSource = null,
        missSource = null,
        resultNote = resultNote,
        archived = archived,
        archivedAtEpochMillis = null
    )

    private fun state(
        instanceId: String,
        status: String = TaskStatuses.READY,
        accumulatedMillis: Long = 0L
    ) = TaskRuntimeStateEntity(
        instanceId = instanceId,
        status = status,
        accumulatedMillis = accumulatedMillis,
        startedAtEpochMillis = null,
        startedAtElapsedRealtimeMillis = null,
        lastPersistedAtEpochMillis = 0L,
        lastBreakReminderAtEpochMillis = null,
        breakUntilEpochMillis = null,
        version = 0L
    )
}
