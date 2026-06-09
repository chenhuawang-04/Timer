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
import kotlin.math.roundToInt

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
    val missedCount: Int,
    val dailyScore: Int
)

data class TaskTotal(
    val instanceId: String,
    val taskName: String,
    val durationMillis: Long
)

data class BreakdownStat(
    val key: String,
    val label: String,
    val trackedMillis: Long,
    val plannedCount: Int,
    val completedCount: Int
) {
    val completionRate: Float = if (plannedCount == 0) 0f else completedCount.toFloat() / plannedCount.toFloat()
}

data class StatsSummary(
    val trackedTodayMillis: Long,
    val trackedWeekMillis: Long,
    val trackedMonthMillis: Long,
    val plannedTodayCount: Int,
    val completedTodayCount: Int,
    val missedTodayCount: Int,
    val cancelledTodayCount: Int,
    val completionRateToday: Float,
    val completionRateWeek: Float,
    val timeWindowCompletedTodayCount: Int,
    val timeWindowMissedTodayCount: Int,
    val timeWindowCompletionRate: Float,
    val countdownCompletedTodayCount: Int,
    val countUpCompletedTodayCount: Int,
    val pomodoroCompletedTodayCount: Int,
    val plannedWindowTodayMillis: Long,
    val completedWindowTodayMillis: Long,
    val missedWindowTodayMillis: Long,
    val averageSessionMillis: Long,
    val focusSessionsTodayCount: Int,
    val currentStreakDays: Int,
    val bestStreakDays: Int,
    val dailyScore: Int,
    val lastSevenDays: List<DailyStat>,
    val topTasks: List<TaskTotal>,
    val categoryBreakdown: List<BreakdownStat>,
    val projectBreakdown: List<BreakdownStat>
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
        referenceDate: LocalDate = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate(),
        weekStartsOn: DayOfWeek = DayOfWeek.MONDAY
    ): StatsSummary {
        val instanceById = instances.associateBy { it.id }
        val today = referenceDate
        val todayString = today.toString()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(weekStartsOn))
        val monthStart = today.withDayOfMonth(1)
        val lastSevenDates = (6 downTo 0).map { today.minusDays(it.toLong()) }

        val dailyTrackedTotals = mutableMapOf<LocalDate, Long>()
        val taskDurationByName = linkedMapOf<String, Long>()
        val taskIdByName = mutableMapOf<String, String>()

        val intervals = buildList {
            sessions.forEach { session ->
                val instance = instanceById[session.instanceId]
                if (session.endedAtEpochMillis > session.startedAtEpochMillis && instance?.status != TaskStatuses.CANCELLED) {
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
            val task = instanceById[interval.instanceId]
            val taskName = task?.nameSnapshot?.ifBlank { "Task" } ?: "Task"
            taskDurationByName[taskName] = (taskDurationByName[taskName] ?: 0L) + duration
            taskIdByName.putIfAbsent(taskName, interval.instanceId)
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

        val weekInstances = instances.filter { instance ->
            val date = LocalDate.parse(instance.localDate)
            !date.isBefore(weekStart) && !date.isAfter(today)
        }
        val weekPlanned = weekInstances.count { it.status != TaskStatuses.CANCELLED }
        val weekCompleted = weekInstances.count { it.status == TaskStatuses.COMPLETED }
        val weekCompletionRate = if (weekPlanned == 0) 0f else weekCompleted.toFloat() / weekPlanned.toFloat()

        val todayTracked = dailyTrackedTotals[today] ?: 0L
        val dailyScore = calculateDailyScore(
            trackedMillis = todayTracked,
            plannedCount = plannedToday.size,
            completedCount = completedToday.size,
            timeWindowRate = timeWindowRate,
            currentStreakSeed = 0
        )

        val lastSevenDays = lastSevenDates.map { date ->
            val dateString = date.toString()
            val dayInstances = instances.filter { it.localDate == dateString }
            val dayCompleted = dayInstances.count { it.status == TaskStatuses.COMPLETED }
            val dayPlanned = dayInstances.count { it.status != TaskStatuses.CANCELLED }
            val dayWindow = dayInstances.filter { it.type == TaskTypes.TIME_WINDOW }
            val dayWindowCompleted = dayWindow.count { it.status == TaskStatuses.COMPLETED }
            val dayWindowMissed = dayWindow.count { it.status == TaskStatuses.MISSED }
            val windowRate = if (dayWindowCompleted + dayWindowMissed == 0) 0f else dayWindowCompleted.toFloat() / (dayWindowCompleted + dayWindowMissed).toFloat()
            DailyStat(
                date = date,
                trackedMillis = dailyTrackedTotals[date] ?: 0L,
                plannedCount = dayPlanned,
                completedCount = dayCompleted,
                missedCount = dayInstances.count { it.status == TaskStatuses.MISSED },
                dailyScore = calculateDailyScore(
                    trackedMillis = dailyTrackedTotals[date] ?: 0L,
                    plannedCount = dayPlanned,
                    completedCount = dayCompleted,
                    timeWindowRate = windowRate,
                    currentStreakSeed = 0
                )
            )
        }

        val completionDates = instances.filter { it.status == TaskStatuses.COMPLETED }
            .map { LocalDate.parse(it.localDate) }
            .distinct()
            .sorted()
        val currentStreak = calculateCurrentStreak(completionDates, today)
        val bestStreak = calculateBestStreak(completionDates)

        val topTasks = taskDurationByName.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { (taskName, duration) ->
                TaskTotal(
                    instanceId = taskIdByName[taskName].orEmpty(),
                    taskName = taskName,
                    durationMillis = duration
                )
            }

        val categoryBreakdown = buildBreakdown(instances, dailyTrackedTotals, intervalLookup = intervals, instanceById = instanceById) { instance ->
            instance.categoryNameSnapshot?.ifBlank { null } ?: "Uncategorized"
        }

        val projectBreakdown = buildBreakdown(instances, dailyTrackedTotals, intervalLookup = intervals, instanceById = instanceById) { instance ->
            instance.projectNameSnapshot?.ifBlank { null } ?: "General"
        }

        return StatsSummary(
            trackedTodayMillis = todayTracked,
            trackedWeekMillis = dailyTrackedTotals.filterKeys { !it.isBefore(weekStart) && !it.isAfter(today) }.values.sum(),
            trackedMonthMillis = dailyTrackedTotals.filterKeys { !it.isBefore(monthStart) && !it.isAfter(today) }.values.sum(),
            plannedTodayCount = plannedToday.size,
            completedTodayCount = completedToday.size,
            missedTodayCount = missedToday.size,
            cancelledTodayCount = cancelledToday.size,
            completionRateToday = if (plannedToday.isEmpty()) 0f else completedToday.size.toFloat() / plannedToday.size.toFloat(),
            completionRateWeek = weekCompletionRate,
            timeWindowCompletedTodayCount = timeWindowCompleted.size,
            timeWindowMissedTodayCount = timeWindowMissed.size,
            timeWindowCompletionRate = timeWindowRate,
            countdownCompletedTodayCount = completedToday.count {
                it.type == TaskTypes.COUNT_DOWN &&
                    (it.completionSource == CompletionSources.COUNTDOWN_AUTO || it.completionSource == CompletionSources.RECOVERED_AUTO)
            },
            countUpCompletedTodayCount = completedToday.count { it.type == TaskTypes.COUNT_UP },
            pomodoroCompletedTodayCount = completedToday.count { it.type == TaskTypes.COUNT_DOWN && PomodoroMath.isPomodoro(it) },
            plannedWindowTodayMillis = timeWindowToday.sumOf { it.plannedDurationMillis() },
            completedWindowTodayMillis = timeWindowCompleted.sumOf { it.plannedDurationMillis() },
            missedWindowTodayMillis = timeWindowMissed.sumOf { it.plannedDurationMillis() },
            averageSessionMillis = if (sessions.isEmpty()) 0L else sessions.map { it.durationMillis }.average().toLong(),
            focusSessionsTodayCount = sessions.count { session ->
                Instant.ofEpochMilli(session.startedAtEpochMillis).atZone(zoneId).toLocalDate() == today
            },
            currentStreakDays = currentStreak,
            bestStreakDays = bestStreak,
            dailyScore = dailyScore,
            lastSevenDays = lastSevenDays,
            topTasks = topTasks,
            categoryBreakdown = categoryBreakdown,
            projectBreakdown = projectBreakdown
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

    private fun buildBreakdown(
        instances: List<TaskInstanceEntity>,
        dailyTrackedTotals: Map<LocalDate, Long>,
        intervalLookup: List<TimedInterval>,
        instanceById: Map<String, TaskInstanceEntity>,
        labelSelector: (TaskInstanceEntity) -> String
    ): List<BreakdownStat> {
        val trackedByLabel = mutableMapOf<String, Long>()
        intervalLookup.forEach { interval ->
            val instance = instanceById[interval.instanceId] ?: return@forEach
            val label = labelSelector(instance)
            val duration = max(0L, interval.endEpochMillis - interval.startEpochMillis)
            trackedByLabel[label] = (trackedByLabel[label] ?: 0L) + duration
        }
        val grouped = instances.groupBy(labelSelector)
        return grouped.map { (label, group) ->
            BreakdownStat(
                key = label,
                label = label,
                trackedMillis = trackedByLabel[label] ?: 0L,
                plannedCount = group.count { it.status != TaskStatuses.CANCELLED },
                completedCount = group.count { it.status == TaskStatuses.COMPLETED }
            )
        }.sortedWith(compareByDescending<BreakdownStat> { it.trackedMillis }.thenByDescending { it.completedCount })
            .take(6)
    }

    private fun calculateCurrentStreak(completionDates: List<LocalDate>, today: LocalDate): Int {
        if (completionDates.isEmpty()) return 0
        var cursor = today
        var streak = 0
        val set = completionDates.toSet()
        while (cursor in set) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun calculateBestStreak(completionDates: List<LocalDate>): Int {
        if (completionDates.isEmpty()) return 0
        var best = 1
        var current = 1
        for (index in 1 until completionDates.size) {
            if (completionDates[index - 1].plusDays(1) == completionDates[index]) {
                current += 1
                best = max(best, current)
            } else if (completionDates[index - 1] != completionDates[index]) {
                current = 1
            }
        }
        return best
    }

    private fun calculateDailyScore(
        trackedMillis: Long,
        plannedCount: Int,
        completedCount: Int,
        timeWindowRate: Float,
        currentStreakSeed: Int
    ): Int {
        val completionScore = if (plannedCount == 0) 0f else (completedCount.toFloat() / plannedCount.toFloat()) * 45f
        val trackedScore = (trackedMillis / 60_000f / 90f).coerceIn(0f, 1f) * 30f
        val windowScore = timeWindowRate.coerceIn(0f, 1f) * 15f
        val streakScore = min(10, currentStreakSeed) * 1f
        return (completionScore + trackedScore + windowScore + streakScore).roundToInt().coerceIn(0, 100)
    }

    private fun TaskInstanceEntity.plannedDurationMillis(): Long {
        val start = plannedStartEpochMillis ?: return 0L
        val end = plannedEndEpochMillis ?: return 0L
        return max(0L, end - start)
    }
}
