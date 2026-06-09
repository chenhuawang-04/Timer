package com.timer.app.data

import androidx.room.withTransaction
import com.timer.app.domain.IdProvider
import com.timer.app.domain.TimerClock
import com.timer.app.domain.TimerMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.max
import kotlin.math.min

data class TimedTaskWithState(
    val instance: TaskInstanceEntity,
    val state: TaskRuntimeStateEntity
)

data class DashboardSnapshot(
    /**
     * All non-archived and archived task instances known locally.
     *
     * The dashboard filters this list for the selected day when rendering task
     * cards, while statistics intentionally need the full local history so week,
     * month, last-seven-days, and top-task totals are not under-counted.
     */
    val instances: List<TaskInstanceEntity>,
    val states: List<TaskRuntimeStateEntity>,
    val sessions: List<TaskSessionEntity>
)

data class CompletedTaskNotification(
    val instanceId: String,
    val taskName: String
)

data class MissedTaskNotification(
    val instanceId: String,
    val taskName: String
)

data class ReconcileResult(
    val completedCountdowns: List<CompletedTaskNotification> = emptyList(),
    val missedTimeWindows: List<MissedTaskNotification> = emptyList()
)

class RoomTimerRepository(
    private val database: TimerDatabase,
    private val clock: TimerClock,
    private val idProvider: IdProvider,
    private val untitledTaskName: String,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val templateDao = database.templateDao()
    private val instanceDao = database.instanceDao()
    private val runtimeStateDao = database.runtimeStateDao()
    private val sessionDao = database.sessionDao()
    private val eventLogDao = database.eventLogDao()

    fun observeDashboardSnapshot(): Flow<DashboardSnapshot> = combine(
        instanceDao.observeAll(),
        runtimeStateDao.observeAll(),
        sessionDao.observeAll()
    ) { instances, states, sessions ->
        DashboardSnapshot(instances = instances, states = states, sessions = sessions)
    }

    fun observeAllInstances(): Flow<List<TaskInstanceEntity>> = instanceDao.observeAll()

    suspend fun getAllInstances(): List<TaskInstanceEntity> = instanceDao.getAll()

    suspend fun createTaskTemplate(
        name: String,
        type: String,
        defaultTargetDurationMillis: Long?,
        defaultStartMinuteOfDay: Int?,
        defaultEndMinuteOfDay: Int?,
        colorArgb: Long,
        icon: String? = null,
        tag: String? = null,
        note: String? = null
    ): String {
        val now = clock.nowEpochMillis()
        val templateId = idProvider.newId()
        database.withTransaction {
            templateDao.upsert(
                TaskTemplateEntity(
                    id = templateId,
                    name = name.sanitizedName(),
                    type = type,
                    defaultTargetDurationMillis = defaultTargetDurationMillis?.coerceAtLeast(1_000L),
                    defaultStartMinuteOfDay = defaultStartMinuteOfDay?.coerceIn(0, 1_439),
                    defaultEndMinuteOfDay = defaultEndMinuteOfDay?.coerceIn(0, 1_439),
                    colorArgb = colorArgb,
                    icon = icon,
                    tag = tag?.trim()?.ifBlank { null },
                    note = note?.trim()?.ifBlank { null },
                    archived = false,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now
                )
            )
            insertEventLocked(
                instanceId = templateId,
                templateId = templateId,
                eventType = TaskEventTypes.CREATE_TEMPLATE,
                atEpochMillis = now,
                elapsedRealtimeMillis = clock.elapsedRealtimeMillis(),
                payloadJson = "{\"type\":\"$type\"}"
            )
        }
        return templateId
    }

    suspend fun createInstanceFromTemplate(templateId: String, localDate: LocalDate): String? {
        val template = templateDao.getById(templateId) ?: return null
        return createTaskInstance(
            name = template.name,
            type = template.type,
            localDate = localDate,
            targetDurationMillis = template.defaultTargetDurationMillis,
            startMinuteOfDay = template.defaultStartMinuteOfDay,
            endMinuteOfDay = template.defaultEndMinuteOfDay,
            colorArgb = template.colorArgb,
            tag = template.tag,
            templateId = template.id
        )
    }

    suspend fun createTaskInstance(
        name: String,
        type: String,
        localDate: LocalDate,
        targetDurationMillis: Long? = null,
        startMinuteOfDay: Int? = null,
        endMinuteOfDay: Int? = null,
        colorArgb: Long,
        tag: String? = null,
        templateId: String? = null
    ): String {
        val now = clock.nowEpochMillis()
        val instanceId = idProvider.newId()
        val plannedStart = if (type == TaskTypes.TIME_WINDOW && startMinuteOfDay != null) {
            epochForMinute(localDate, startMinuteOfDay)
        } else {
            null
        }
        val plannedEnd = if (type == TaskTypes.TIME_WINDOW && startMinuteOfDay != null && endMinuteOfDay != null) {
            val endDate = if (endMinuteOfDay <= startMinuteOfDay) localDate.plusDays(1) else localDate
            epochForMinute(endDate, endMinuteOfDay)
        } else {
            null
        }
        val status = initialStatus(type, plannedStart, plannedEnd, now)
        database.withTransaction {
            val instance = TaskInstanceEntity(
                id = instanceId,
                templateId = templateId,
                localDate = localDate.toString(),
                nameSnapshot = name.sanitizedName(),
                type = type,
                status = status,
                targetDurationMillis = if (type == TaskTypes.COUNT_DOWN) targetDurationMillis?.coerceAtLeast(1_000L) else null,
                plannedStartEpochMillis = plannedStart,
                plannedEndEpochMillis = plannedEnd,
                colorArgb = colorArgb,
                tagSnapshot = tag?.trim()?.ifBlank { null },
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                completedAtEpochMillis = null,
                missedAtEpochMillis = if (status == TaskStatuses.MISSED) now else null,
                cancelledAtEpochMillis = null,
                completionSource = null,
                missSource = if (status == TaskStatuses.MISSED) MissSources.DEADLINE_AUTO else null,
                archived = false,
                archivedAtEpochMillis = null
            )
            instanceDao.upsert(instance)
            if (type == TaskTypes.COUNT_UP || type == TaskTypes.COUNT_DOWN) {
                runtimeStateDao.upsert(TaskRuntimeStateEntity.idle(instanceId, now))
            }
            insertEventLocked(
                instanceId = instanceId,
                templateId = templateId,
                eventType = TaskEventTypes.CREATE_INSTANCE,
                atEpochMillis = now,
                elapsedRealtimeMillis = clock.elapsedRealtimeMillis(),
                payloadJson = "{\"type\":\"$type\",\"localDate\":\"$localDate\"}"
            )
        }
        return instanceId
    }

    suspend fun getRunningTimedTasksWithStates(): List<TimedTaskWithState> {
        val instances = instanceDao.getAll().associateBy { it.id }
        return runtimeStateDao.getByStatus(TaskStatuses.RUNNING).mapNotNull { state ->
            val instance = instances[state.instanceId] ?: return@mapNotNull null
            if (!instance.archived && instance.status == TaskStatuses.RUNNING && instance.type != TaskTypes.TIME_WINDOW) {
                TimedTaskWithState(instance, state)
            } else {
                null
            }
        }
    }

    suspend fun startInstance(instanceId: String) {
        database.withTransaction {
            val now = clock.nowEpochMillis()
            val nowElapsed = clock.elapsedRealtimeMillis()
            val instance = instanceDao.getById(instanceId) ?: return@withTransaction
            if (instance.archived) return@withTransaction
            if (instance.type == TaskTypes.TIME_WINDOW) return@withTransaction
            if (instance.status !in listOf(TaskStatuses.READY, TaskStatuses.PAUSED)) return@withTransaction
            val existing = runtimeStateDao.getByInstanceId(instanceId) ?: TaskRuntimeStateEntity.idle(instanceId, now)
            val accumulated = if (existing.status == TaskStatuses.PAUSED) existing.accumulatedMillis else 0L
            val runningInstance = instance.copy(
                status = TaskStatuses.RUNNING,
                updatedAtEpochMillis = now,
                completedAtEpochMillis = null,
                completionSource = null,
                cancelledAtEpochMillis = null
            )
            instanceDao.upsert(runningInstance)
            runtimeStateDao.upsert(
                existing.copy(
                    status = TaskStatuses.RUNNING,
                    accumulatedMillis = if (instance.type == TaskTypes.COUNT_DOWN) {
                        min(accumulated, instance.targetDurationMillis ?: Long.MAX_VALUE)
                    } else {
                        accumulated
                    },
                    startedAtEpochMillis = now,
                    startedAtElapsedRealtimeMillis = nowElapsed,
                    lastPersistedAtEpochMillis = now,
                    version = existing.version + 1
                )
            )
            insertEventLocked(instanceId, instance.templateId, TaskEventTypes.START, now, nowElapsed, null)
        }
    }

    suspend fun pauseInstance(instanceId: String) {
        database.withTransaction {
            val now = clock.nowEpochMillis()
            val nowElapsed = clock.elapsedRealtimeMillis()
            val instance = instanceDao.getById(instanceId) ?: return@withTransaction
            val state = runtimeStateDao.getByInstanceId(instanceId) ?: return@withTransaction
            if (instance.archived) return@withTransaction
            if (state.status != TaskStatuses.RUNNING || instance.status != TaskStatuses.RUNNING) return@withTransaction
            val segment = TimerMath.clampCountdownSegment(instance, state, TimerMath.currentOpenSegmentMillis(state, nowElapsed))
            val newAccumulated = state.accumulatedMillis + segment
            val completed = instance.type == TaskTypes.COUNT_DOWN && instance.targetDurationMillis != null && newAccumulated >= instance.targetDurationMillis
            insertSessionForOpenSegmentLocked(
                instance = instance,
                state = state,
                nowEpochMillis = now,
                durationMillis = segment,
                source = if (completed) SessionSources.COUNTDOWN_AUTO else SessionSources.MANUAL
            )
            if (completed) {
                markInstanceCompletedLocked(instance, CompletionSources.COUNTDOWN_AUTO, now, nowElapsed)
                runtimeStateDao.upsert(state.completedCopy(instance.targetDurationMillis ?: newAccumulated, now))
            } else {
                instanceDao.upsert(instance.copy(status = TaskStatuses.PAUSED, updatedAtEpochMillis = now))
                runtimeStateDao.upsert(
                    state.copy(
                        status = TaskStatuses.PAUSED,
                        accumulatedMillis = newAccumulated,
                        startedAtEpochMillis = null,
                        startedAtElapsedRealtimeMillis = null,
                        lastPersistedAtEpochMillis = now,
                        version = state.version + 1
                    )
                )
                insertEventLocked(instanceId, instance.templateId, TaskEventTypes.PAUSE, now, nowElapsed, null)
            }
        }
    }

    suspend fun resumeInstance(instanceId: String) {
        database.withTransaction {
            val now = clock.nowEpochMillis()
            val nowElapsed = clock.elapsedRealtimeMillis()
            val instance = instanceDao.getById(instanceId) ?: return@withTransaction
            val state = runtimeStateDao.getByInstanceId(instanceId) ?: return@withTransaction
            if (instance.archived) return@withTransaction
            if (instance.type == TaskTypes.TIME_WINDOW || instance.status != TaskStatuses.PAUSED || state.status != TaskStatuses.PAUSED) return@withTransaction
            instanceDao.upsert(instance.copy(status = TaskStatuses.RUNNING, updatedAtEpochMillis = now))
            runtimeStateDao.upsert(
                state.copy(
                    status = TaskStatuses.RUNNING,
                    startedAtEpochMillis = now,
                    startedAtElapsedRealtimeMillis = nowElapsed,
                    lastPersistedAtEpochMillis = now,
                    version = state.version + 1
                )
            )
            insertEventLocked(instanceId, instance.templateId, TaskEventTypes.RESUME, now, nowElapsed, null)
        }
    }

    suspend fun completeInstanceManually(instanceId: String) {
        database.withTransaction {
            val now = clock.nowEpochMillis()
            val nowElapsed = clock.elapsedRealtimeMillis()
            val instance = instanceDao.getById(instanceId) ?: return@withTransaction
            if (instance.archived) return@withTransaction
            when (instance.type) {
                TaskTypes.COUNT_UP -> completeCountUpLocked(instance, now, nowElapsed)
                TaskTypes.TIME_WINDOW -> completeTimeWindowLocked(instance, now, nowElapsed)
                else -> return@withTransaction
            }
        }
    }

    suspend fun cancelInstance(instanceId: String) {
        database.withTransaction {
            val now = clock.nowEpochMillis()
            val nowElapsed = clock.elapsedRealtimeMillis()
            val instance = instanceDao.getById(instanceId) ?: return@withTransaction
            if (instance.archived) return@withTransaction
            if (instance.status in listOf(TaskStatuses.COMPLETED, TaskStatuses.MISSED, TaskStatuses.CANCELLED)) return@withTransaction
            if (
                instance.type == TaskTypes.TIME_WINDOW &&
                instance.plannedEndEpochMillis != null &&
                now >= instance.plannedEndEpochMillis
            ) {
                val source = MissSources.DEADLINE_AUTO
                instanceDao.upsert(
                    instance.copy(
                        status = TaskStatuses.MISSED,
                        updatedAtEpochMillis = now,
                        missedAtEpochMillis = now,
                        missSource = source
                    )
                )
                insertEventLocked(instance.id, instance.templateId, TaskEventTypes.MISS, now, nowElapsed, "{\"source\":\"cancel_after_deadline\"}")
                return@withTransaction
            }
            instanceDao.upsert(
                instance.copy(
                    status = TaskStatuses.CANCELLED,
                    updatedAtEpochMillis = now,
                    cancelledAtEpochMillis = now
                )
            )
            runtimeStateDao.getByInstanceId(instanceId)?.let { state ->
                runtimeStateDao.upsert(
                    state.copy(
                        status = TaskStatuses.CANCELLED,
                        startedAtEpochMillis = null,
                        startedAtElapsedRealtimeMillis = null,
                        lastPersistedAtEpochMillis = now,
                        version = state.version + 1
                    )
                )
            }
            insertEventLocked(instanceId, instance.templateId, TaskEventTypes.CANCEL, now, nowElapsed, null)
        }
    }

    suspend fun archiveInstance(instanceId: String) {
        database.withTransaction {
            val now = clock.nowEpochMillis()
            val instance = instanceDao.getById(instanceId) ?: return@withTransaction
            if (instance.archived) return@withTransaction
            if (instance.status !in listOf(TaskStatuses.COMPLETED, TaskStatuses.MISSED, TaskStatuses.CANCELLED)) return@withTransaction
            instanceDao.upsert(
                instance.copy(
                    archived = true,
                    archivedAtEpochMillis = now,
                    updatedAtEpochMillis = now
                )
            )
            insertEventLocked(instanceId, instance.templateId, TaskEventTypes.ARCHIVE, now, clock.elapsedRealtimeMillis(), null)
        }
    }

    suspend fun reconcileDeadlines(recoveredAfterBoot: Boolean = false): ReconcileResult {
        val completed = mutableListOf<CompletedTaskNotification>()
        val missed = mutableListOf<MissedTaskNotification>()
        database.withTransaction {
            val now = clock.nowEpochMillis()
            val nowElapsed = clock.elapsedRealtimeMillis()

            instanceDao.getReadyTimeWindows(TaskTypes.TIME_WINDOW, TaskStatuses.PLANNED, now).forEach { instance ->
                instanceDao.upsert(instance.copy(status = TaskStatuses.READY, updatedAtEpochMillis = now))
            }

            instanceDao.getExpiredTimeWindows(
                type = TaskTypes.TIME_WINDOW,
                statuses = listOf(TaskStatuses.PLANNED, TaskStatuses.READY),
                nowEpochMillis = now
            ).forEach { instance ->
                val source = if (recoveredAfterBoot) MissSources.RECOVERED_DEADLINE else MissSources.DEADLINE_AUTO
                instanceDao.upsert(
                    instance.copy(
                        status = TaskStatuses.MISSED,
                        updatedAtEpochMillis = now,
                        missedAtEpochMillis = now,
                        missSource = source
                    )
                )
                insertEventLocked(instance.id, instance.templateId, TaskEventTypes.MISS, now, nowElapsed, "{\"source\":\"$source\"}")
                missed.add(MissedTaskNotification(instance.id, instance.nameSnapshot))
            }

            runtimeStateDao.getByStatus(TaskStatuses.RUNNING).forEach { state ->
                val instance = instanceDao.getById(state.instanceId) ?: return@forEach
                if (instance.archived || instance.type != TaskTypes.COUNT_DOWN || instance.status != TaskStatuses.RUNNING) return@forEach
                if (TimerMath.isExpiredCountdown(instance, state, nowElapsed)) {
                    completeCountdownLocked(
                        instance = instance,
                        state = state,
                        completionSource = CompletionSources.COUNTDOWN_AUTO,
                        sessionSource = SessionSources.COUNTDOWN_AUTO,
                        nowEpoch = now,
                        nowElapsed = nowElapsed
                    )?.let(completed::add)
                }
            }
        }
        return ReconcileResult(completedCountdowns = completed, missedTimeWindows = missed)
    }

    suspend fun recoverAfterBoot(): ReconcileResult {
        val completed = mutableListOf<CompletedTaskNotification>()
        database.withTransaction {
            val now = clock.nowEpochMillis()
            val nowElapsed = clock.elapsedRealtimeMillis()
            runtimeStateDao.getByStatus(TaskStatuses.RUNNING).forEach { state ->
                val instance = instanceDao.getById(state.instanceId) ?: return@forEach
                if (instance.archived || instance.type !in listOf(TaskTypes.COUNT_UP, TaskTypes.COUNT_DOWN) || instance.status != TaskStatuses.RUNNING) return@forEach
                val startedAtEpoch = state.startedAtEpochMillis ?: now
                val wallDelta = max(0L, now - startedAtEpoch)
                if (wallDelta <= 0L) {
                    runtimeStateDao.upsert(
                        state.copy(
                            startedAtEpochMillis = now,
                            startedAtElapsedRealtimeMillis = nowElapsed,
                            lastPersistedAtEpochMillis = now,
                            version = state.version + 1
                        )
                    )
                    insertEventLocked(instance.id, instance.templateId, TaskEventTypes.RECOVER, now, nowElapsed, "{\"mode\":\"boot_zero_delta\"}")
                    return@forEach
                }

                if (instance.type == TaskTypes.COUNT_DOWN && instance.targetDurationMillis != null) {
                    val needed = max(0L, instance.targetDurationMillis - state.accumulatedMillis)
                    if (wallDelta >= needed) {
                        insertSessionLocked(instance, startedAtEpoch, needed, SessionSources.RECOVERED_COMPLETED, now)
                        markInstanceCompletedLocked(instance, CompletionSources.RECOVERED_AUTO, now, nowElapsed)
                        runtimeStateDao.upsert(state.completedCopy(instance.targetDurationMillis, now))
                        completed.add(CompletedTaskNotification(instance.id, instance.nameSnapshot))
                    } else {
                        insertSessionLocked(instance, startedAtEpoch, wallDelta, SessionSources.RECOVERED_PARTIAL, now)
                        runtimeStateDao.upsert(
                            state.copy(
                                accumulatedMillis = state.accumulatedMillis + wallDelta,
                                startedAtEpochMillis = now,
                                startedAtElapsedRealtimeMillis = nowElapsed,
                                lastPersistedAtEpochMillis = now,
                                version = state.version + 1
                            )
                        )
                        insertEventLocked(instance.id, instance.templateId, TaskEventTypes.RECOVER, now, nowElapsed, "{\"mode\":\"boot_partial_countdown\"}")
                    }
                } else {
                    insertSessionLocked(instance, startedAtEpoch, wallDelta, SessionSources.RECOVERED_PARTIAL, now)
                    runtimeStateDao.upsert(
                        state.copy(
                            accumulatedMillis = state.accumulatedMillis + wallDelta,
                            startedAtEpochMillis = now,
                            startedAtElapsedRealtimeMillis = nowElapsed,
                            lastPersistedAtEpochMillis = now,
                            version = state.version + 1
                        )
                    )
                    insertEventLocked(instance.id, instance.templateId, TaskEventTypes.RECOVER, now, nowElapsed, "{\"mode\":\"boot_count_up\"}")
                }
            }
        }
        val deadlineResult = reconcileDeadlines(recoveredAfterBoot = true)
        return ReconcileResult(
            completedCountdowns = completed + deadlineResult.completedCountdowns,
            missedTimeWindows = deadlineResult.missedTimeWindows
        )
    }

    private suspend fun completeCountUpLocked(instance: TaskInstanceEntity, now: Long, nowElapsed: Long) {
        if (instance.status !in listOf(TaskStatuses.READY, TaskStatuses.RUNNING, TaskStatuses.PAUSED)) return
        val state = runtimeStateDao.getByInstanceId(instance.id)
        if (state != null && state.status == TaskStatuses.RUNNING) {
            val segment = TimerMath.currentOpenSegmentMillis(state, nowElapsed)
            insertSessionForOpenSegmentLocked(instance, state, now, segment, SessionSources.MANUAL)
            runtimeStateDao.upsert(state.completedCopy(state.accumulatedMillis + segment, now))
        } else if (state != null) {
            runtimeStateDao.upsert(state.completedCopy(state.accumulatedMillis, now))
        }
        markInstanceCompletedLocked(instance, CompletionSources.MANUAL, now, nowElapsed)
    }

    private suspend fun completeTimeWindowLocked(instance: TaskInstanceEntity, now: Long, nowElapsed: Long) {
        val start = instance.plannedStartEpochMillis ?: return
        val end = instance.plannedEndEpochMillis ?: return
        if (now < start) return
        if (now >= end) {
            instanceDao.upsert(
                instance.copy(
                    status = TaskStatuses.MISSED,
                    updatedAtEpochMillis = now,
                    missedAtEpochMillis = now,
                    missSource = MissSources.DEADLINE_AUTO
                )
            )
            insertEventLocked(instance.id, instance.templateId, TaskEventTypes.MISS, now, nowElapsed, "{\"source\":\"manual_attempt_after_deadline\"}")
            return
        }
        if (instance.status !in listOf(TaskStatuses.PLANNED, TaskStatuses.READY)) return
        markInstanceCompletedLocked(instance, CompletionSources.MANUAL, now, nowElapsed)
    }

    private suspend fun completeCountdownLocked(
        instance: TaskInstanceEntity,
        state: TaskRuntimeStateEntity,
        completionSource: String,
        sessionSource: String,
        nowEpoch: Long,
        nowElapsed: Long
    ): CompletedTaskNotification? {
        val target = instance.targetDurationMillis ?: return null
        val segment = TimerMath.clampCountdownSegment(instance, state, TimerMath.currentOpenSegmentMillis(state, nowElapsed))
        insertSessionForOpenSegmentLocked(instance, state, nowEpoch, segment, sessionSource)
        markInstanceCompletedLocked(instance, completionSource, nowEpoch, nowElapsed)
        runtimeStateDao.upsert(state.completedCopy(target, nowEpoch))
        return CompletedTaskNotification(instance.id, instance.nameSnapshot)
    }

    private suspend fun markInstanceCompletedLocked(
        instance: TaskInstanceEntity,
        completionSource: String,
        nowEpoch: Long,
        nowElapsed: Long
    ) {
        instanceDao.upsert(
            instance.copy(
                status = TaskStatuses.COMPLETED,
                updatedAtEpochMillis = nowEpoch,
                completedAtEpochMillis = nowEpoch,
                completionSource = completionSource,
                missedAtEpochMillis = null,
                missSource = null,
                cancelledAtEpochMillis = null
            )
        )
        insertEventLocked(instance.id, instance.templateId, TaskEventTypes.COMPLETE, nowEpoch, nowElapsed, "{\"source\":\"$completionSource\"}")
    }

    private suspend fun insertSessionForOpenSegmentLocked(
        instance: TaskInstanceEntity,
        state: TaskRuntimeStateEntity,
        nowEpochMillis: Long,
        durationMillis: Long,
        source: String
    ) {
        val safeDuration = max(0L, durationMillis)
        if (safeDuration <= 0L) return
        val startEpoch = state.startedAtEpochMillis ?: (nowEpochMillis - safeDuration)
        val endEpoch = min(nowEpochMillis, startEpoch + safeDuration)
        val normalizedDuration = max(0L, endEpoch - startEpoch)
        if (normalizedDuration <= 0L) return
        insertSessionLocked(instance, startEpoch, normalizedDuration, source, nowEpochMillis)
    }

    private suspend fun insertSessionLocked(
        instance: TaskInstanceEntity,
        startEpochMillis: Long,
        durationMillis: Long,
        source: String,
        createdAtEpochMillis: Long
    ) {
        if (durationMillis <= 0L) return
        sessionDao.insert(
            TaskSessionEntity(
                id = idProvider.newId(),
                instanceId = instance.id,
                templateId = instance.templateId,
                startedAtEpochMillis = startEpochMillis,
                endedAtEpochMillis = startEpochMillis + durationMillis,
                durationMillis = durationMillis,
                source = source,
                createdAtEpochMillis = createdAtEpochMillis
            )
        )
    }

    private suspend fun insertEventLocked(
        instanceId: String,
        templateId: String?,
        eventType: String,
        atEpochMillis: Long,
        elapsedRealtimeMillis: Long?,
        payloadJson: String?
    ) {
        eventLogDao.insert(
            TaskEventLogEntity(
                id = idProvider.newId(),
                instanceId = instanceId,
                templateId = templateId,
                eventType = eventType,
                atEpochMillis = atEpochMillis,
                elapsedRealtimeMillis = elapsedRealtimeMillis,
                payloadJson = payloadJson
            )
        )
    }

    private fun initialStatus(type: String, start: Long?, end: Long?, now: Long): String = when (type) {
        TaskTypes.TIME_WINDOW -> when {
            end == null || start == null -> TaskStatuses.PLANNED
            now >= end -> TaskStatuses.MISSED
            now >= start -> TaskStatuses.READY
            else -> TaskStatuses.PLANNED
        }
        else -> TaskStatuses.READY
    }

    private fun epochForMinute(localDate: LocalDate, minuteOfDay: Int): Long {
        return localDate.atStartOfDay(zoneId)
            .plusMinutes(minuteOfDay.coerceIn(0, 1_439).toLong())
            .toInstant()
            .toEpochMilli()
    }

    private fun String.sanitizedName(): String = trim().ifBlank { untitledTaskName }

    private fun TaskRuntimeStateEntity.completedCopy(accumulated: Long, now: Long): TaskRuntimeStateEntity = copy(
        status = TaskStatuses.COMPLETED,
        accumulatedMillis = accumulated.coerceAtLeast(0L),
        startedAtEpochMillis = null,
        startedAtElapsedRealtimeMillis = null,
        lastPersistedAtEpochMillis = now,
        version = version + 1
    )
}
