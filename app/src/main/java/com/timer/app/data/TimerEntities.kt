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

object TaskEventTypes {
    const val CREATE_TEMPLATE = "CREATE_TEMPLATE"
    const val CREATE_INSTANCE = "CREATE_INSTANCE"
    const val START = "START"
    const val PAUSE = "PAUSE"
    const val RESUME = "RESUME"
    const val COMPLETE = "COMPLETE"
    const val MISS = "MISS"
    const val CANCEL = "CANCEL"
    const val RECOVER = "RECOVER"
    const val ARCHIVE = "ARCHIVE"
}

@Entity(
    tableName = "task_template",
    indices = [
        Index(value = ["archived"]),
        Index(value = ["type"]),
        Index(value = ["tag"])
    ]
)
data class TaskTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val defaultTargetDurationMillis: Long?,
    val defaultStartMinuteOfDay: Int?,
    val defaultEndMinuteOfDay: Int?,
    val colorArgb: Long,
    val icon: String?,
    val tag: String?,
    val note: String?,
    val archived: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "task_instance",
    indices = [
        Index(value = ["templateId"]),
        Index(value = ["localDate"]),
        Index(value = ["status"]),
        Index(value = ["archived"]),
        Index(value = ["type"]),
        Index(value = ["plannedStartEpochMillis"]),
        Index(value = ["plannedEndEpochMillis"])
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
    val plannedStartEpochMillis: Long?,
    val plannedEndEpochMillis: Long?,
    val colorArgb: Long,
    val tagSnapshot: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val missedAtEpochMillis: Long?,
    val cancelledAtEpochMillis: Long?,
    val completionSource: String?,
    val missSource: String?,
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
