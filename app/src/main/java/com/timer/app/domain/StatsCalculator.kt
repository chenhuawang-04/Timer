package com.timer.app.domain

import com.timer.app.data.CompletionSources
import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskRuntimeStateEntity
import com.timer.app.data.TaskSessionEntity
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min

private data class TimedInterval(
    val instanceId: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long
)

data class DailyStat(
    val date: LocalDate,
    val trackedMillis: Long,
    val plannedCount: Int,
    val completedCount: Int,
    val missedCount: Int
)

data class TaskTotal(
    val instanceId: String,
    val taskName: String,
    val durationMillis: Long
)

data class StatsSummary(
    val trackedTodayMillis: Long,
    val trackedWeekMillis: Long,
    val trackedMonthMillis: Long,
    val plannedTodayCount: Int,
    val completedTodayCount: Int,
    val missedTodayCount: Int,
    val cancelledTodayCount: Int,
    val timeWindowCompletedTodayCount: Int,
    val timeWindowMissedTodayCount: Int,
    val timeWindowCompletionRate: Float,
    val countdownCompletedTodayCount: Int,
    val countUpCompletedTodayCount: Int,
    val plannedWindowTodayMillis: Long,
    val completedWindowTodayMillis: Long,
    val missedWindowTodayMillis: Long,
    val lastSevenDays: List<DailyStat>,
    val topTasks: List<TaskTotal>
) {
    val todayMillis: Long get() = trackedTodayMillis
    val weekMillis: Long get() = trackedWeekMillis
    val monthMillis: Long get() = trackedMonthMillis
    val completedCountdownCount: Int get() = countdownCompletedTodayCount
}

object StatsCalculator {
    fun empty(
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): StatsSummary = calculate(
        instances = emptyList(),
        states = emptyList(),
        sessions = emptyList(),
        nowEpochMillis = nowEpochMillis,
        nowElapsedRealtimeMillis = 0L,
        zoneId = zoneId
    )

    fun calculate(
        instances: List<TaskInstanceEntity>,
        states: List<TaskRuntimeStateEntity>,
        sessions: List<TaskSessionEntity>,
        nowEpochMillis: Long,
        nowElapsedRealtimeMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        weekStartsOn: DayOfWeek = DayOfWeek.MONDAY
    ): StatsSummary {
        val instanceById = instances.associateBy { it.id }
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
        val todayString = today.toString()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(weekStartsOn))
        val monthStart = today.withDayOfMonth(1)
        val lastSevenDates = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val dailyTrackedTotals = mutableMapOf<LocalDate, Long>()
        val instanceTotals = mutableMapOf<String, Long>()

        val intervals = buildList {
            sessions.forEach { session ->
                val instance = instanceById[session.instanceId]
                if (
                    session.endedAtEpochMillis > session.startedAtEpochMillis &&
                    instance?.status != TaskStatuses.CANCELLED
                ) {
                    add(
                        TimedInterval(
                            instanceId = session.instanceId,
                            startEpochMillis = session.startedAtEpochMillis,
                            endEpochMillis = session.endedAtEpochMillis
                        )
                    )
                }
            }
            states.filter { it.status == TaskStatuses.RUNNING }.forEach { state ->
                val instance = instanceById[state.instanceId] ?: return@forEach
                if (instance.archived) return@forEach
                if (instance.type == TaskTypes.TIME_WINDOW) return@forEach
                val openStartEpoch = state.startedAtEpochMillis ?: nowEpochMillis
                val rawSegment = TimerMath.currentOpenSegmentMillis(state, nowElapsedRealtimeMillis)
                val segment = if (instance.type == TaskTypes.COUNT_DOWN) {
                    TimerMath.clampCountdownSegment(instance, state, rawSegment)
                } else {
                    rawSegment
                }
                if (segment > 0L) {
                    add(
                        TimedInterval(
                            instanceId = state.instanceId,
                            startEpochMillis = openStartEpoch,
                            endEpochMillis = min(nowEpochMillis, openStartEpoch + segment)
                        )
                    )
                }
            }
        }

        intervals.forEach { interval ->
            val duration = max(0L, interval.endEpochMillis - interval.startEpochMillis)
            if (duration <= 0L) return@forEach
            instanceTotals[interval.instanceId] = (instanceTotals[interval.instanceId] ?: 0L) + duration
            splitByLocalDate(interval.startEpochMillis, interval.endEpochMillis, zoneId).forEach { (date, millis) ->
                dailyTrackedTotals[date] = (dailyTrackedTotals[date] ?: 0L) + millis
            }
        }

        val todayInstances = instances.filter { it.localDate == todayString }
        val plannedToday = todayInstances.filterNot { it.status == TaskStatuses.CANCELLED }
        val completedToday = todayInstances.filter { it.status == TaskStatuses.COMPLETED }
        val missedToday = todayInstances.filter { it.status == TaskStatuses.MISSED }
        val cancelledToday = todayInstances.filter { it.status == TaskStatuses.CANCELLED }
        val timeWindowToday = todayInstances.filter { it.type == TaskTypes.TIME_WINDOW }
        val timeWindowCompleted = timeWindowToday.filter { it.status == TaskStatuses.COMPLETED }
        val timeWindowMissed = timeWindowToday.filter { it.status == TaskStatuses.MISSED }
        val timeWindowDenominator = timeWindowCompleted.size + timeWindowMissed.size
        val timeWindowRate = if (timeWindowDenominator == 0) 0f else timeWindowCompleted.size.toFloat() / timeWindowDenominator.toFloat()

        val topTasks = instanceTotals.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { (instanceId, duration) ->
                TaskTotal(
                    instanceId = instanceId,
                    taskName = instanceById[instanceId]?.nameSnapshot ?: "Deleted task",
                    durationMillis = duration
                )
            }

        return StatsSummary(
            trackedTodayMillis = dailyTrackedTotals[today] ?: 0L,
            trackedWeekMillis = dailyTrackedTotals.filterKeys { !it.isBefore(weekStart) && !it.isAfter(today) }.values.sum(),
            trackedMonthMillis = dailyTrackedTotals.filterKeys { !it.isBefore(monthStart) && !it.isAfter(today) }.values.sum(),
            plannedTodayCount = plannedToday.size,
            completedTodayCount = completedToday.size,
            missedTodayCount = missedToday.size,
            cancelledTodayCount = cancelledToday.size,
            timeWindowCompletedTodayCount = timeWindowCompleted.size,
            timeWindowMissedTodayCount = timeWindowMissed.size,
            timeWindowCompletionRate = timeWindowRate,
            countdownCompletedTodayCount = completedToday.count {
                it.type == TaskTypes.COUNT_DOWN &&
                    (it.completionSource == CompletionSources.COUNTDOWN_AUTO || it.completionSource == CompletionSources.RECOVERED_AUTO)
            },
            countUpCompletedTodayCount = completedToday.count { it.type == TaskTypes.COUNT_UP },
            plannedWindowTodayMillis = timeWindowToday.sumOf { it.plannedDurationMillis() },
            completedWindowTodayMillis = timeWindowCompleted.sumOf { it.plannedDurationMillis() },
            missedWindowTodayMillis = timeWindowMissed.sumOf { it.plannedDurationMillis() },
            lastSevenDays = lastSevenDates.map { date ->
                val dateString = date.toString()
                val dayInstances = instances.filter { it.localDate == dateString }
                DailyStat(
                    date = date,
                    trackedMillis = dailyTrackedTotals[date] ?: 0L,
                    plannedCount = dayInstances.count { it.status != TaskStatuses.CANCELLED },
                    completedCount = dayInstances.count { it.status == TaskStatuses.COMPLETED },
                    missedCount = dayInstances.count { it.status == TaskStatuses.MISSED }
                )
            },
            topTasks = topTasks
        )
    }

    fun splitByLocalDate(
        startEpochMillis: Long,
        endEpochMillis: Long,
        zoneId: ZoneId
    ): Map<LocalDate, Long> {
        if (endEpochMillis <= startEpochMillis) return emptyMap()
        val result = linkedMapOf<LocalDate, Long>()
        var cursor = Instant.ofEpochMilli(startEpochMillis).atZone(zoneId)
        val end = Instant.ofEpochMilli(endEpochMillis).atZone(zoneId)

        while (cursor.toInstant().toEpochMilli() < endEpochMillis) {
            val date = cursor.toLocalDate()
            val nextMidnight: ZonedDateTime = date.plusDays(1).atStartOfDay(zoneId)
            val segmentEnd = min(nextMidnight.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            val segmentStart = cursor.toInstant().toEpochMilli()
            val duration = max(0L, segmentEnd - segmentStart)
            if (duration > 0L) {
                result[date] = (result[date] ?: 0L) + duration
            }
            cursor = Instant.ofEpochMilli(segmentEnd).atZone(zoneId)
        }
        return result
    }

    private fun TaskInstanceEntity.plannedDurationMillis(): Long {
        val start = plannedStartEpochMillis ?: return 0L
        val end = plannedEndEpochMillis ?: return 0L
        return max(0L, end - start)
    }
}
