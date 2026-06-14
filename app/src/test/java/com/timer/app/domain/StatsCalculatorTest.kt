package com.timer.app.domain

import com.timer.app.data.CompletionSources
import com.timer.app.data.MissSources
import com.timer.app.data.SessionModes
import com.timer.app.data.SessionSources
import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskRuntimeStateEntity
import com.timer.app.data.TaskSessionEntity
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsCalculatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun splitByLocalDateSplitsCrossMidnightSession() {
        val start = Instant.parse("2026-06-08T15:50:00Z").toEpochMilli() // 23:50 Shanghai
        val end = Instant.parse("2026-06-08T16:20:00Z").toEpochMilli() // 00:20 Shanghai next day

        val split = StatsCalculator.splitByLocalDate(start, end, zone)

        assertEquals(10 * 60_000L, split[LocalDate.of(2026, 6, 8)])
        assertEquals(20 * 60_000L, split[LocalDate.of(2026, 6, 9)])
    }

    @Test
    fun calculateIncludesOpenRunningStateWithoutPersistingEveryTick() {
        val instance = instance(id = "task", name = "Deep work", type = TaskTypes.COUNT_UP, status = TaskStatuses.RUNNING)
        val nowEpoch = Instant.parse("2026-06-09T01:00:00Z").toEpochMilli()
        val state = runtimeState(
            instanceId = instance.id,
            status = TaskStatuses.RUNNING,
            accumulatedMillis = 30 * 60_000L,
            startedAtEpochMillis = nowEpoch - 10 * 60_000L,
            startedAtElapsedRealtimeMillis = 1_000_000L
        )

        val stats = StatsCalculator.calculate(
            instances = listOf(instance),
            states = listOf(state),
            sessions = emptyList(),
            nowEpochMillis = nowEpoch,
            nowElapsedRealtimeMillis = 1_600_000L,
            zoneId = zone
        )

        assertEquals(10 * 60_000L, stats.trackedTodayMillis)
        assertEquals("Deep work", stats.topTasks.single().taskName)
        assertEquals(10 * 60_000L, stats.topTasks.single().durationMillis)
    }

    @Test
    fun timeWindowCompletedAndMissedStatsUsePlannedDurationsNotTrackedTime() {
        val completed = timeWindow(
            id = "window_done",
            name = "Reading",
            status = TaskStatuses.COMPLETED,
            start = Instant.parse("2026-06-08T12:00:00Z").toEpochMilli(),
            end = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            completedAtEpochMillis = Instant.parse("2026-06-08T12:30:00Z").toEpochMilli()
        )
        val missed = timeWindow(
            id = "window_missed",
            name = "Workout",
            status = TaskStatuses.MISSED,
            start = Instant.parse("2026-06-08T14:00:00Z").toEpochMilli(),
            end = Instant.parse("2026-06-08T14:30:00Z").toEpochMilli(),
            missedAtEpochMillis = Instant.parse("2026-06-08T14:30:00Z").toEpochMilli()
        )

        val stats = StatsCalculator.calculate(
            instances = listOf(completed, missed),
            states = emptyList(),
            sessions = emptyList(),
            nowEpochMillis = Instant.parse("2026-06-08T15:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone
        )

        assertEquals(2, stats.plannedTodayCount)
        assertEquals(1, stats.timeWindowCompletedTodayCount)
        assertEquals(1, stats.timeWindowMissedTodayCount)
        assertEquals(0.5f, stats.timeWindowCompletionRate, 0.0001f)
        assertEquals(90 * 60_000L, stats.plannedWindowTodayMillis)
        assertEquals(60 * 60_000L, stats.completedWindowTodayMillis)
        assertEquals(30 * 60_000L, stats.missedWindowTodayMillis)
        assertEquals(0L, stats.trackedTodayMillis)
    }

    @Test
    fun pomodoroCompletedTasksAreCountedSeparately() {
        val instance = instance(
            id = "pomodoro",
            name = "Focus",
            type = TaskTypes.COUNT_DOWN,
            status = TaskStatuses.COMPLETED,
            completedAtEpochMillis = Instant.parse("2026-06-08T12:55:00Z").toEpochMilli(),
            completionSource = CompletionSources.COUNTDOWN_AUTO,
            sessionMode = SessionModes.POMODORO,
            pomodoroWorkMinutes = 25,
            pomodoroBreakMinutes = 5,
            pomodoroCycles = 2,
            targetDurationMillis = 55 * 60_000L
        )

        val stats = StatsCalculator.calculate(
            instances = listOf(instance),
            states = emptyList(),
            sessions = listOf(session(instanceId = instance.id, start = Instant.parse("2026-06-08T12:00:00Z").toEpochMilli(), durationMillis = 55 * 60_000L)),
            nowEpochMillis = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone
        )

        assertEquals(1, stats.countdownCompletedTodayCount)
        assertEquals(1, stats.pomodoroCompletedTodayCount)
        assertTrue(stats.dailyScore > 0)
    }

    @Test
    fun categoryAndProjectBreakdownAggregateTrackedTime() {
        val a = instance(id = "a", name = "Write", type = TaskTypes.COUNT_UP, categoryName = "Work", projectName = "App")
        val b = instance(id = "b", name = "Read", type = TaskTypes.COUNT_UP, categoryName = "Study", projectName = "Exam")
        val start = Instant.parse("2026-06-08T10:00:00Z").toEpochMilli()

        val stats = StatsCalculator.calculate(
            instances = listOf(a, b),
            states = emptyList(),
            sessions = listOf(
                session(instanceId = a.id, start = start, durationMillis = 30 * 60_000L),
                session(instanceId = b.id, start = start, durationMillis = 45 * 60_000L)
            ),
            nowEpochMillis = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone
        )

        assertEquals("Study", stats.categoryBreakdown.first().label)
        assertEquals("Exam", stats.projectBreakdown.first().label)
    }

    @Test
    fun calculateCanAnchorDailyStatsToSelectedDate() {
        val selectedDate = LocalDate.of(2026, 6, 8)
        val nowEpoch = Instant.parse("2026-06-09T13:00:00Z").toEpochMilli()
        val selectedDayTask = instance(id = "selected", name = "Review", type = TaskTypes.COUNT_UP, status = TaskStatuses.COMPLETED)
        val currentDayTask = instance(id = "current", name = "Ship", type = TaskTypes.COUNT_UP, status = TaskStatuses.READY)
            .copy(localDate = "2026-06-09")

        val stats = StatsCalculator.calculate(
            instances = listOf(selectedDayTask, currentDayTask),
            states = emptyList(),
            sessions = listOf(session(instanceId = selectedDayTask.id, start = Instant.parse("2026-06-08T01:00:00Z").toEpochMilli(), durationMillis = 20 * 60_000L)),
            nowEpochMillis = nowEpoch,
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone,
            referenceDate = selectedDate
        )

        assertEquals(1, stats.plannedTodayCount)
        assertEquals(1, stats.completedTodayCount)
        assertEquals(20 * 60_000L, stats.trackedTodayMillis)
        assertEquals(selectedDate, stats.lastSevenDays.last().date)
    }

    @Test
    fun calculateExcludesArchivedAndFutureInstancesFromInsightBreakdowns() {
        val referenceDate = LocalDate.of(2026, 6, 8)
        val completed = instance(
            id = "completed",
            name = "Write",
            type = TaskTypes.COUNT_UP,
            status = TaskStatuses.COMPLETED,
            categoryName = "Work",
            projectName = "App"
        )
        val futureGenerated = instance(
            id = "future",
            name = "Future plan",
            type = TaskTypes.COUNT_UP,
            status = TaskStatuses.READY,
            categoryName = "Work",
            projectName = "App"
        ).copy(localDate = "2026-07-20")
        val archived = instance(
            id = "archived",
            name = "Archived plan",
            type = TaskTypes.COUNT_UP,
            status = TaskStatuses.READY,
            archived = true,
            categoryName = "Archived",
            projectName = "Old"
        )

        val stats = StatsCalculator.calculate(
            instances = listOf(completed, futureGenerated, archived),
            states = emptyList(),
            sessions = listOf(
                session(
                    instanceId = completed.id,
                    start = Instant.parse("2026-06-08T01:00:00Z").toEpochMilli(),
                    durationMillis = 20 * 60_000L
                ),
                session(
                    instanceId = futureGenerated.id,
                    start = Instant.parse("2026-07-20T01:00:00Z").toEpochMilli(),
                    durationMillis = 60 * 60_000L
                )
            ),
            nowEpochMillis = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone,
            referenceDate = referenceDate
        )

        assertEquals(1, stats.plannedTodayCount)
        assertEquals(1, stats.completedTodayCount)
        val work = stats.categoryBreakdown.single { it.label == "Work" }
        assertEquals(1, work.plannedCount)
        assertEquals(1, work.completedCount)
        assertEquals(20 * 60_000L, work.trackedMillis)
        val app = stats.projectBreakdown.single { it.label == "App" }
        assertEquals(1, app.plannedCount)
        assertEquals(1, app.completedCount)
        assertEquals(20 * 60_000L, app.trackedMillis)
        assertFalse(stats.categoryBreakdown.any { it.label == "Archived" })
        assertFalse(stats.projectBreakdown.any { it.label == "Old" })
    }

    private fun instance(
        id: String,
        name: String,
        type: String,
        status: String = TaskStatuses.READY,
        targetDurationMillis: Long? = null,
        completedAtEpochMillis: Long? = null,
        completionSource: String? = null,
        archived: Boolean = false,
        categoryName: String? = null,
        projectName: String? = null,
        sessionMode: String = SessionModes.STANDARD,
        pomodoroWorkMinutes: Int? = null,
        pomodoroBreakMinutes: Int? = null,
        pomodoroCycles: Int? = null
    ) = TaskInstanceEntity(
        id = id,
        templateId = null,
        localDate = "2026-06-08",
        nameSnapshot = name,
        type = type,
        status = status,
        targetDurationMillis = targetDurationMillis,
        preferredStartEpochMillis = null,
        plannedStartEpochMillis = null,
        plannedEndEpochMillis = null,
        colorArgb = 0xFF0284C7,
        categoryIdSnapshot = null,
        categoryNameSnapshot = categoryName,
        projectNameSnapshot = projectName,
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
        completedAtEpochMillis = completedAtEpochMillis,
        missedAtEpochMillis = null,
        cancelledAtEpochMillis = if (status == TaskStatuses.CANCELLED) 1L else null,
        completionSource = completionSource,
        missSource = null,
        resultNote = null,
        archived = archived,
        archivedAtEpochMillis = if (archived) 2L else null
    )

    private fun timeWindow(
        id: String,
        name: String,
        status: String,
        start: Long,
        end: Long,
        completedAtEpochMillis: Long? = null,
        missedAtEpochMillis: Long? = null,
        archived: Boolean = false
    ) = TaskInstanceEntity(
        id = id,
        templateId = null,
        localDate = "2026-06-08",
        nameSnapshot = name,
        type = TaskTypes.TIME_WINDOW,
        status = status,
        targetDurationMillis = null,
        preferredStartEpochMillis = null,
        plannedStartEpochMillis = start,
        plannedEndEpochMillis = end,
        colorArgb = 0xFF0F766E,
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
        sessionMode = SessionModes.STANDARD,
        pomodoroWorkMinutes = null,
        pomodoroBreakMinutes = null,
        pomodoroCycles = null,
        sortOrder = 0,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        completedAtEpochMillis = completedAtEpochMillis,
        missedAtEpochMillis = missedAtEpochMillis,
        cancelledAtEpochMillis = null,
        completionSource = if (completedAtEpochMillis != null) CompletionSources.MANUAL else null,
        missSource = if (missedAtEpochMillis != null) MissSources.DEADLINE_AUTO else null,
        resultNote = null,
        archived = archived,
        archivedAtEpochMillis = if (archived) 2L else null
    )

    private fun runtimeState(
        instanceId: String,
        status: String,
        accumulatedMillis: Long,
        startedAtEpochMillis: Long?,
        startedAtElapsedRealtimeMillis: Long?
    ) = TaskRuntimeStateEntity(
        instanceId = instanceId,
        status = status,
        accumulatedMillis = accumulatedMillis,
        startedAtEpochMillis = startedAtEpochMillis,
        startedAtElapsedRealtimeMillis = startedAtElapsedRealtimeMillis,
        lastPersistedAtEpochMillis = startedAtEpochMillis ?: 0L,
        lastBreakReminderAtEpochMillis = null,
        breakUntilEpochMillis = null,
        version = 1L
    )

    private fun session(
        instanceId: String,
        start: Long,
        durationMillis: Long,
        source: String = SessionSources.MANUAL
    ) = TaskSessionEntity(
        id = "session_$instanceId$source",
        instanceId = instanceId,
        templateId = null,
        startedAtEpochMillis = start,
        endedAtEpochMillis = start + durationMillis,
        durationMillis = durationMillis,
        source = source,
        createdAtEpochMillis = start + durationMillis
    )
}
