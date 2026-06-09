package com.timer.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object TaskTypes {
    const val COUNT_UP = "COUNT_UP"
    const val COUNT_DOWN = "COUNT_DOWN"
    const val TIME_WINDOW = "TIME_WINDOW"
}

object TaskStatuses {
    const val PLANNED = "PLANNED"
    const val READY = "READY"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val MISSED = "MISSED"
    const val CANCELLED = "CANCELLED"
}

object SessionModes {
    const val STANDARD = "STANDARD"
    const val POMODORO = "POMODORO"
}

object TaskPriorities {
    const val LOW = "LOW"
    const val MEDIUM = "MEDIUM"
    const val HIGH = "HIGH"
}

object RepeatModes {
    const val NONE = "NONE"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val WEEKDAYS = "WEEKDAYS"
    const val CUSTOM_DAYS = "CUSTOM_DAYS"
    const val MONTHLY = "MONTHLY"
}

object SessionSources {
    const val MANUAL = "MANUAL"
    const val COUNTDOWN_AUTO = "COUNTDOWN_AUTO"
    const val RECOVERED_PARTIAL = "RECOVERED_PARTIAL"
    const val RECOVERED_COMPLETED = "RECOVERED_COMPLETED"
}

object CompletionSources {
    const val MANUAL = "MANUAL"
    const val COUNTDOWN_AUTO = "COUNTDOWN_AUTO"
    const val RECOVERED_AUTO = "RECOVERED_AUTO"
}

object MissSources {
    const val DEADLINE_AUTO = "DEADLINE_AUTO"
    const val RECOVERED_DEADLINE = "RECOVERED_DEADLINE"
}

object GoalScopes {
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
}

object GoalMetricTypes {
    const val COMPLETED_TASKS = "COMPLETED_TASKS"
    const val TRACKED_MINUTES = "TRACKED_MINUTES"
    const val TIME_WINDOW_COMPLETION_RATE = "TIME_WINDOW_COMPLETION_RATE"
}

object TaskEventTypes {
    const val CREATE_CATEGORY = "CREATE_CATEGORY"
    const val CREATE_GOAL = "CREATE_GOAL"
    const val CREATE_TEMPLATE = "CREATE_TEMPLATE"
    const val CREATE_INSTANCE = "CREATE_INSTANCE"
    const val GENERATE_INSTANCE = "GENERATE_INSTANCE"
    const val START = "START"
    const val PAUSE = "PAUSE"
    const val RESUME = "RESUME"
    const val COMPLETE = "COMPLETE"
    const val MISS = "MISS"
    const val CANCEL = "CANCEL"
    const val RECOVER = "RECOVER"
    const val ARCHIVE = "ARCHIVE"
    const val UPDATE_NOTE = "UPDATE_NOTE"
    const val EXPORT = "EXPORT"
    const val IMPORT = "IMPORT"
}

object AlarmKinds {
    const val TASK_START = "TASK_START"
    const val WINDOW_PRE_END = "WINDOW_PRE_END"
    const val WINDOW_DEADLINE = "WINDOW_DEADLINE"
    const val COUNTDOWN_COMPLETE = "COUNTDOWN_COMPLETE"
}

@Entity(
    tableName = "task_category",
    indices = [
        Index(value = ["archived"]),
        Index(value = ["name"], unique = true)
    ]
)
data class TaskCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long,
    val archived: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "goal",
    indices = [
        Index(value = ["active"]),
        Index(value = ["scope"]),
        Index(value = ["metricType"]),
        Index(value = ["categoryId"])
    ]
)
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val scope: String,
    val metricType: String,
    val targetValue: Long,
    val categoryId: String?,
    val projectName: String?,
    val active: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "task_template",
    indices = [
        Index(value = ["archived"]),
        Index(value = ["type"]),
        Index(value = ["categoryId"]),
        Index(value = ["repeatMode"]),
        Index(value = ["projectName"])
    ]
)
data class TaskTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val defaultTargetDurationMillis: Long?,
    val preferredStartMinuteOfDay: Int?,
    val defaultStartMinuteOfDay: Int?,
    val defaultEndMinuteOfDay: Int?,
    val colorArgb: Long,
    val categoryId: String?,
    val projectName: String?,
    val tagsCsv: String?,
    val note: String?,
    val priority: String,
    val anchorDate: String,
    val repeatMode: String,
    val repeatDaysCsv: String?,
    val repeatInterval: Int,
    val remindersEnabled: Boolean,
    val remindAtStart: Boolean,
    val remindBeforeEndMinutes: Int?,
    val remindAtDeadline: Boolean,
    val countTowardGoals: Boolean,
    val sessionMode: String,
    val pomodoroWorkMinutes: Int?,
    val pomodoroBreakMinutes: Int?,
    val pomodoroCycles: Int?,
    val autoGenerateAheadDays: Int,
    val archived: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "task_instance",
    indices = [
        Index(value = ["templateId", "localDate"], unique = true),
        Index(value = ["localDate"]),
        Index(value = ["status"]),
        Index(value = ["archived"]),
        Index(value = ["type"]),
        Index(value = ["plannedStartEpochMillis"]),
        Index(value = ["plannedEndEpochMillis"]),
        Index(value = ["categoryIdSnapshot"]),
        Index(value = ["projectNameSnapshot"]),
        Index(value = ["priority"])
    ]
)
data class TaskInstanceEntity(
    @PrimaryKey val id: String,
    val templateId: String?,
    val localDate: String,
    val nameSnapshot: String,
    val type: String,
    val status: String,
    val targetDurationMillis: Long?,
    val preferredStartEpochMillis: Long?,
    val plannedStartEpochMillis: Long?,
    val plannedEndEpochMillis: Long?,
    val colorArgb: Long,
    val categoryIdSnapshot: String?,
    val categoryNameSnapshot: String?,
    val projectNameSnapshot: String?,
    val tagsSnapshot: String?,
    val noteSnapshot: String?,
    val priority: String,
    val remindersEnabled: Boolean,
    val remindAtStart: Boolean,
    val remindBeforeEndMinutes: Int?,
    val remindAtDeadline: Boolean,
    val countTowardGoals: Boolean,
    val sessionMode: String,
    val pomodoroWorkMinutes: Int?,
    val pomodoroBreakMinutes: Int?,
    val pomodoroCycles: Int?,
    val sortOrder: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val missedAtEpochMillis: Long?,
    val cancelledAtEpochMillis: Long?,
    val completionSource: String?,
    val missSource: String?,
    val resultNote: String?,
    val archived: Boolean,
    val archivedAtEpochMillis: Long?
)

@Entity(tableName = "task_runtime_state")
data class TaskRuntimeStateEntity(
    @PrimaryKey val instanceId: String,
    val status: String,
    val accumulatedMillis: Long,
    val startedAtEpochMillis: Long?,
    val startedAtElapsedRealtimeMillis: Long?,
    val lastPersistedAtEpochMillis: Long,
    val version: Long
) {
    companion object {
        fun idle(instanceId: String, nowEpochMillis: Long): TaskRuntimeStateEntity =
            TaskRuntimeStateEntity(
                instanceId = instanceId,
                status = TaskStatuses.READY,
                accumulatedMillis = 0L,
                startedAtEpochMillis = null,
                startedAtElapsedRealtimeMillis = null,
                lastPersistedAtEpochMillis = nowEpochMillis,
                version = 0L
            )
    }
}

@Entity(
    tableName = "task_session",
    indices = [
        Index(value = ["instanceId"]),
        Index(value = ["templateId"]),
        Index(value = ["startedAtEpochMillis"]),
        Index(value = ["endedAtEpochMillis"])
    ]
)
data class TaskSessionEntity(
    @PrimaryKey val id: String,
    val instanceId: String,
    val templateId: String?,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val durationMillis: Long,
    val source: String,
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "task_event_log",
    indices = [
        Index(value = ["instanceId"]),
        Index(value = ["templateId"]),
        Index(value = ["eventType"]),
        Index(value = ["atEpochMillis"])
    ]
)
data class TaskEventLogEntity(
    @PrimaryKey val id: String,
    val instanceId: String,
    val templateId: String?,
    val eventType: String,
    val atEpochMillis: Long,
    val elapsedRealtimeMillis: Long?,
    val payloadJson: String?
)
