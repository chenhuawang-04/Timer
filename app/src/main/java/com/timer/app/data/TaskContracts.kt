package com.timer.app.data

data class TaskDraft(
    val name: String,
    val type: String,
    val localDate: String,
    val countdownMinutes: Long?,
    val preferredStartTime: String?,
    val startTime: String?,
    val endTime: String?,
    val categoryId: String?,
    val projectName: String?,
    val tags: String?,
    val note: String?,
    val priority: String,
    val saveAsRoutine: Boolean,
    val repeatMode: String,
    val repeatDaysCsv: String?,
    val repeatInterval: Int,
    val remindersEnabled: Boolean,
    val remindAtStart: Boolean,
    val remindBeforeEndMinutes: Int?,
    val remindAtDeadline: Boolean,
    val sessionMode: String,
    val pomodoroWorkMinutes: Int?,
    val pomodoroBreakMinutes: Int?,
    val pomodoroCycles: Int?,
    val colorArgb: Long,
    val countTowardGoals: Boolean = true
)

data class GoalDraft(
    val name: String,
    val scope: String,
    val metricType: String,
    val targetValue: Long,
    val categoryId: String?,
    val projectName: String?
)

data class CategoryDraft(
    val name: String,
    val colorArgb: Long
)

data class ImportSummary(
    val categoryCount: Int,
    val goalCount: Int,
    val templateCount: Int,
    val instanceCount: Int,
    val sessionCount: Int,
    val eventCount: Int
)

data class RepositoryExportData(
    val categories: List<TaskCategoryEntity>,
    val goals: List<GoalEntity>,
    val templates: List<TaskTemplateEntity>,
    val instances: List<TaskInstanceEntity>,
    val states: List<TaskRuntimeStateEntity>,
    val sessions: List<TaskSessionEntity>,
    val events: List<TaskEventLogEntity>
)
