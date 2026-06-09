package com.timer.app.domain

import com.timer.app.data.CompletionSources
import com.timer.app.data.MissSources
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
    fun calculateUsesImmutableSessionsForHistoricalStats() {
        val instance = instance(id = "task", name = "Study", type = TaskTypes.COUNT_UP)
        val start = Instant.parse("2026-06-08T12:00:00Z").toEpochMilli()
        val session = session(instanceId = instance.id, start = start, durationMillis = 45 * 60_000L)

        val stats = StatsCalculator.calculate(
            instances = listOf(instance),
            states = emptyList(),
            sessions = listOf(session),
            nowEpochMillis = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone
        )

        assertEquals(45 * 60_000L, stats.trackedTodayMillis)
        assertEquals(45 * 60_000L, stats.trackedWeekMillis)
        assertEquals(45 * 60_000L, stats.trackedMonthMillis)
    }

    @Test
    fun countdownCompletionCountComesFromCompletedInstanceSourceNotPartialRecoverySessions() {
        val partial = instance(
            id = "partial",
            name = "Tea partial",
            type = TaskTypes.COUNT_DOWN,
            status = TaskStatuses.RUNNING,
            targetDurationMillis = 15 * 60_000L
        )
        val completed = instance(
            id = "completed",
            name = "Tea completed",
            type = TaskTypes.COUNT_DOWN,
            status = TaskStatuses.COMPLETED,
            targetDurationMillis = 15 * 60_000L,
            completedAtEpochMillis = Instant.parse("2026-06-08T12:15:00Z").toEpochMilli(),
            completionSource = CompletionSources.RECOVERED_AUTO
        )
        val start = Instant.parse("2026-06-08T12:00:00Z").toEpochMilli()
        val sessions = listOf(
            session(instanceId = partial.id, start = start, durationMillis = 10 * 60_000L, source = SessionSources.RECOVERED_PARTIAL),
            session(instanceId = completed.id, start = start, durationMillis = 15 * 60_000L, source = SessionSources.RECOVERED_COMPLETED)
        )

        val stats = StatsCalculator.calculate(
            instances = listOf(partial, completed),
            states = emptyList(),
            sessions = sessions,
            nowEpochMillis = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone
        )

        assertEquals(1, stats.countdownCompletedTodayCount)
        assertEquals(1, stats.completedCountdownCount)
    }

    @Test
    fun autoCompletedCountdownIsCountedAndContributesTrackedDuration() {
        val instance = instance(
            id = "countdown",
            name = "Tea",
            type = TaskTypes.COUNT_DOWN,
            status = TaskStatuses.COMPLETED,
            targetDurationMillis = 15 * 60_000L,
            completedAtEpochMillis = Instant.parse("2026-06-08T12:15:00Z").toEpochMilli(),
            completionSource = CompletionSources.COUNTDOWN_AUTO
        )
        val start = Instant.parse("2026-06-08T12:00:00Z").toEpochMilli()

        val stats = StatsCalculator.calculate(
            instances = listOf(instance),
            states = emptyList(),
            sessions = listOf(session(instanceId = instance.id, start = start, durationMillis = 15 * 60_000L, source = SessionSources.COUNTDOWN_AUTO)),
            nowEpochMillis = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone
        )

        assertEquals(1, stats.countdownCompletedTodayCount)
        assertEquals(15 * 60_000L, stats.trackedTodayMillis)
    }

    @Test
    fun countUpManualCompletionAndMultipleDailyInstancesAreIncludedInTaskStats() {
        val completedCountUp = instance(
            id = "countup_done",
            name = "Write",
            type = TaskTypes.COUNT_UP,
            status = TaskStatuses.COMPLETED,
            completedAtEpochMillis = Instant.parse("2026-06-08T10:00:00Z").toEpochMilli(),
            completionSource = CompletionSources.MANUAL
        )
        val readyCountUp = instance(id = "countup_ready", name = "Read", type = TaskTypes.COUNT_UP)
        val cancelled = instance(id = "cancelled", name = "Cancelled", type = TaskTypes.COUNT_UP, status = TaskStatuses.CANCELLED)

        val stats = StatsCalculator.calculate(
            instances = listOf(completedCountUp, readyCountUp, cancelled),
            states = emptyList(),
            sessions = emptyList(),
            nowEpochMillis = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone
        )

        assertEquals(2, stats.plannedTodayCount)
        assertEquals(1, stats.completedTodayCount)
        assertEquals(1, stats.cancelledTodayCount)
        assertEquals(1, stats.countUpCompletedTodayCount)
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
    fun archivedCompletedTimedTaskStillContributesToHistoricalStatistics() {
        val archivedCompleted = instance(
            id = "archived_done",
            name = "Archived writing",
            type = TaskTypes.COUNT_UP,
            status = TaskStatuses.COMPLETED,
            completedAtEpochMillis = Instant.parse("2026-06-08T12:45:00Z").toEpochMilli(),
            completionSource = CompletionSources.MANUAL,
            archived = true
        )
        val start = Instant.parse("2026-06-08T12:00:00Z").toEpochMilli()

        val stats = StatsCalculator.calculate(
            instances = listOf(archivedCompleted),
            states = emptyList(),
            sessions = listOf(session(instanceId = archivedCompleted.id, start = start, durationMillis = 45 * 60_000L)),
            nowEpochMillis = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone
        )

        assertEquals(45 * 60_000L, stats.trackedTodayMillis)
        assertEquals(1, stats.completedTodayCount)
        assertEquals(1, stats.countUpCompletedTodayCount)
    }

    @Test
    fun archivedMissedTimeWindowStillContributesToMissedStatistics() {
        val archivedMissed = timeWindow(
            id = "archived_window_missed",
            name = "Archived missed window",
            status = TaskStatuses.MISSED,
            start = Instant.parse("2026-06-08T12:00:00Z").toEpochMilli(),
            end = Instant.parse("2026-06-08T12:30:00Z").toEpochMilli(),
            missedAtEpochMillis = Instant.parse("2026-06-08T12:30:00Z").toEpochMilli(),
            archived = true
        )

        val stats = StatsCalculator.calculate(
            instances = listOf(archivedMissed),
            states = emptyList(),
            sessions = emptyList(),
            nowEpochMillis = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
            nowElapsedRealtimeMillis = 0L,
            zoneId = zone
        )

        assertEquals(1, stats.plannedTodayCount)
        assertEquals(1, stats.missedTodayCount)
        assertEquals(1, stats.timeWindowMissedTodayCount)
        assertEquals(30 * 60_000L, stats.missedWindowTodayMillis)
    }

    private fun instance(
        id: String,
        name: String,
        type: String,
        status: String = TaskStatuses.READY,
        targetDurationMillis: Long? = null,
        completedAtEpochMillis: Long? = null,
        completionSource: String? = null,
        archived: Boolean = false
    ) = TaskInstanceEntity(
        id = id,
        templateId = null,
        localDate = "2026-06-08",
        nameSnapshot = name,
        type = type,
        status = status,
        targetDurationMillis = targetDurationMillis,
        plannedStartEpochMillis = null,
        plannedEndEpochMillis = null,
        colorArgb = 0xFF0284C7,
        tagSnapshot = null,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        completedAtEpochMillis = completedAtEpochMillis,
        missedAtEpochMillis = null,
        cancelledAtEpochMillis = if (status == TaskStatuses.CANCELLED) 1L else null,
        completionSource = completionSource,
        missSource = null,
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
        plannedStartEpochMillis = start,
        plannedEndEpochMillis = end,
        colorArgb = 0xFF0F766E,
        tagSnapshot = null,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        completedAtEpochMillis = completedAtEpochMillis,
        missedAtEpochMillis = missedAtEpochMillis,
        cancelledAtEpochMillis = null,
        completionSource = if (completedAtEpochMillis != null) CompletionSources.MANUAL else null,
        missSource = if (missedAtEpochMillis != null) MissSources.DEADLINE_AUTO else null,
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
