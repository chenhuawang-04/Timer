package com.timer.app.domain

import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes

data class InsightSuggestion(
    val title: String,
    val body: String
)

object SuggestionEngine {
    fun buildSuggestions(
        stats: StatsSummary,
        selectedDateInstances: List<TaskInstanceEntity>
    ): List<InsightSuggestion> {
        val suggestions = mutableListOf<InsightSuggestion>()
        if (selectedDateInstances.isEmpty()) {
            suggestions += InsightSuggestion(
                title = "Start with one routine",
                body = "Create one recurring task first so your daily plan and statistics have a stable anchor."
            )
        }
        if (stats.missedTodayCount > 0) {
            suggestions += InsightSuggestion(
                title = "Reduce misses",
                body = "Today's missed tasks suggest adding an earlier reminder or shrinking the time window."
            )
        }
        if (stats.timeWindowMissedTodayCount > stats.timeWindowCompletedTodayCount && stats.timeWindowMissedTodayCount > 0) {
            suggestions += InsightSuggestion(
                title = "Adjust time windows",
                body = "Most window tasks were missed today. Consider earlier start reminders or shorter daily load."
            )
        }
        val unfinishedHighPriority = selectedDateInstances.count {
            it.priority == com.timer.app.data.TaskPriorities.HIGH &&
                it.status !in setOf(TaskStatuses.COMPLETED, TaskStatuses.CANCELLED)
        }
        if (unfinishedHighPriority >= 2) {
            suggestions += InsightSuggestion(
                title = "Focus the high-priority list",
                body = "You still have multiple high-priority tasks open. Move one into focus mode and complete it first."
            )
        }
        if (stats.currentStreakDays >= 3) {
            suggestions += InsightSuggestion(
                title = "Protect your streak",
                body = "You are on a ${stats.currentStreakDays}-day streak. Keep at least one important routine simple today."
            )
        }
        if (selectedDateInstances.any { it.type == TaskTypes.COUNT_DOWN && it.sessionMode == com.timer.app.data.SessionModes.POMODORO }) {
            suggestions += InsightSuggestion(
                title = "Use pomodoro breaks well",
                body = "Break phases are great for short resets. Keep them brief so the next work cycle starts on time."
            )
        }
        return suggestions.take(4)
    }
}
