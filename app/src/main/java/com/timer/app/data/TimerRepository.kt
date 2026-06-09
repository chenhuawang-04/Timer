package com.timer.app.data

import androidx.room.withTransaction
import com.timer.app.domain.IdProvider
import com.timer.app.domain.PomodoroMath
import com.timer.app.domain.TaskRecurrence
import com.timer.app.domain.TimerClock
import com.timer.app.domain.TimerMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class TimedTaskWithState(
    val instance: TaskInstanceEntity,
    val state: TaskRuntimeStateEntity
)

data class AppSnapshot(
    val categories: List<TaskCategoryEntity>,
    val goals: List<GoalEntity>,
    val templates: List<TaskTemplateEntity>,
    val instances: List<TaskInstanceEntity>,
    val states: List<TaskRuntimeStateEntity>,
    val sessions: List<TaskSessionEntity>,
    val events: List<TaskEventLogEntity>
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
    private val defaultCategories: List<Pair<String, Long>>,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private data class MetaSnapshot(
        val categories: List<TaskCategoryEntity>,
        val goals: List<GoalEntity>,
        val templates: List<TaskTemplateEntity>
    )

    private data class RuntimeSnapshot(
        val instances: List<TaskInstanceEntity>,
        val states: List<TaskRuntimeStateEntity>,
        val sessions: List<TaskSessionEntity>,
        val events: List<TaskEventLogEntity>
    )

    private val categoryDao = database.categoryDao()
    private val goalDao = database.goalDao()
    private val templateDao = database.templateDao()
    private val instanceDao = database.instanceDao()
    private val runtimeStateDao = database.runtimeStateDao()
    private val sessionDao = database.sessionDao()
    private val eventLogDao = database.eventLogDao()

    fun observeAppSnapshot(): Flow<AppSnapshot> {
        val metaFlow = combine(
            categoryDao.observeActive(),
            goalDao.observeActive(),
            templateDao.observeActiveTemplates()
        ) { categories, goals, templates ->
            MetaSnapshot(categories, goals, templates)
        }
        val runtimeFlow = combine(
            instanceDao.observeAll(),
            runtimeStateDao.observeAll(),
            sessionDao.observeAll(),
            eventLogDao.observeAll()
        ) { instances, states, sessions, events ->
            RuntimeSnapshot(instances, states, sessions, events)
        }
        return combine(metaFlow, runtimeFlow) { meta, runtime ->
            AppSnapshot(
                categories = meta.categories,
                goals = meta.goals,
                templates = meta.templates,
                instances = runtime.instances,
                states = runtime.states,
                sessions = runtime.sessions,
                events = runtime.events
            )
        }
    }

    suspend fun ensureSeedData() {
        if (categoryDao.getAll().isNotEmpty()) return
        val now = clock.nowEpochMillis()
        val defaults = defaultCategories.map { (name, colorArgb) ->
            TaskCategoryEntity(idProvider.newId(), name, colorArgb, false, now, now)
        }
        database.withTransaction {
            categoryDao.upsertAll(defaults)
            defaults.forEach { category ->
                insertEventLocked(
                    instanceId = category.id,
                    templateId = null,
                    eventType = TaskEventTypes.CREATE_CATEGORY,
                    atEpochMillis = now,
                    elapsedRealtimeMillis = clock.elapsedRealtimeMillis(),
                    payloadJson = "{\"name\":\"${category.name}\"}"
                )
            }
        }
    }

    suspend fun exportData(): RepositoryExportData = withContext(Dispatchers.IO) {
        RepositoryExportData(
            categories = categoryDao.getAll(),
            goals = goalDao.getAll(),
            templates = templateDao.getAll(),
            instances = instanceDao.getAll(),
            states = runtimeStateDao.getAll(),
            sessions = sessionDao.getAll(),
            events = eventLogDao.getAll()
        )
    }

    suspend fun importData(data: RepositoryExportData): ImportSummary = withContext(Dispatchers.IO) {
        database.clearAllTables()
        database.withTransaction {
            categoryDao.upsertAll(data.categories)
            goalDao.upsertAll(data.goals)
            templateDao.upsertAll(data.templates)
            instanceDao.upsertAll(data.instances)
            runtimeStateDao.upsertAll(data.states)
            sessionDao.upsertAll(data.sessions)
            eventLogDao.upsertAll(data.events)
            insertEventLocked(
                instanceId = "system",
                templateId = null,
                eventType = TaskEventTypes.IMPORT,
                atEpochMillis = clock.nowEpochMillis(),
                elapsedRealtimeMillis = clock.elapsedRealtimeMillis(),
                payloadJson = "{\"instances\":${data.instances.size}}"
            )
        }
        ImportSummary(
            categoryCount = data.categories.size,
            goalCount = data.goals.size,
            templateCount = data.templates.size,
            instanceCount = data.instances.size,
            sessionCount = data.sessions.size,
            eventCount = data.events.size
        )
    }

    suspend fun getAllInstances(): List<TaskInstanceEntity> = instanceDao.getAll()

    suspend fun getAllRuntimeStates(): List<TaskRuntimeStateEntity> = runtimeStateDao.getAll()

    suspend fun getAllSessions(): List<TaskSessionEntity> = sessionDao.getAll()

    suspend fun createCategory(draft: CategoryDraft): String {
        val now = clock.nowEpochMillis()
        val existing = categoryDao.getByName(draft.name.trim())
        if (existing != null) return existing.id
        val category = TaskCategoryEntity(
            id = idProvider.newId(),
            name = draft.name.trim(),
            colorArgb = draft.colorArgb,
            archived = false,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        database.withTransaction {
            categoryDao.upsert(category)
            insertEventLocked(category.id, null, TaskEventTypes.CREATE_CATEGORY, now, clock.elapsedRealtimeMillis(), "{\"name\":\"${category.name}\"}")
        }
        return category.id
    }

    suspend fun createGoal(draft: GoalDraft): String {
        val now = clock.nowEpochMillis()
        val goal = GoalEntity(
            id = idProvider.newId(),
            name = draft.name.trim().ifBlank { "Goal" },
            scope = draft.scope,
            metricType = draft.metricType,
            targetValue = draft.targetValue.coerceAtLeast(1L),
            categoryId = draft.categoryId,
            projectName = draft.projectName?.trim()?.ifBlank { null },
            active = true,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        database.withTransaction {
            goalDao.upsert(goal)
            insertEventLocked(goal.id, null, TaskEventTypes.CREATE_GOAL, now, clock.elapsedRealtimeMillis(), "{\"scope\":\"${goal.scope}\"}")
        }
        return goal.id
    }

    suspend fun preparePlanningWindow(anchorDate: LocalDate, pastDays: Long = 14L, futureDays: Long = 7L) {
        ensureSeedData()
        val start = anchorDate.minusDays(pastDays)
        val end = anchorDate.plusDays(futureDays)
        val nowDate = Instant.ofEpochMilli(clock.nowEpochMillis()).atZone(zoneId).toLocalDate()
        val activeTemplates = templateDao.getActiveTemplates()
        val categoriesById = categoryDao.getAll().associateBy { it.id }
        val existing = instanceDao.getForDateRange(start.toString(), end.toString())
            .filter { it.templateId != null }
            .associateBy { requireNotNull(it.templateId) to it.localDate }
        val newInstances = mutableListOf<TaskInstanceEntity>()
        val newStates = mutableListOf<TaskRuntimeStateEntity>()
        val newEvents = mutableListOf<TaskEventLogEntity>()
        val nowEpoch = clock.nowEpochMillis()
        val nowElapsed = clock.elapsedRealtimeMillis()

        activeTemplates.forEach { template ->
            val generationEnd = minOf(end, nowDate.plusDays(template.autoGenerateAheadDays.coerceIn(0, 7).toLong()))
            var cursor = maxOf(start, LocalDate.parse(template.anchorDate))
            while (!cursor.isAfter(generationEnd)) {
                val key = template.id to cursor.toString()
                if (key !in existing && TaskRecurrence.matches(template, cursor)) {
                    val category = template.categoryId?.let(categoriesById::get)
                    val instance = buildInstanceFromTemplate(
                        template = template,
                        localDate = cursor,
                        categoryName = category?.name,
                        nowEpoch = nowEpoch,
                        sortOrder = defaultSortOrder(
                            priority = template.priority,
                            minuteOfDay = template.preferredStartMinuteOfDay ?: template.defaultStartMinuteOfDay
                        ),
                        generated = true
                    )
                    newInstances += instance
                    if (instance.type != TaskTypes.TIME_WINDOW) {
                        newStates += TaskRuntimeStateEntity.idle(instance.id, nowEpoch)
                    }
                    newEvents += TaskEventLogEntity(
                        id = idProvider.newId(),
                        instanceId = instance.id,
                        templateId = template.id,
                        eventType = TaskEventTypes.GENERATE_INSTANCE,
                        atEpochMillis = nowEpoch,
                        elapsedRealtimeMillis = nowElapsed,
                        payloadJson = "{\"localDate\":\"${cursor}\"}"
                    )
                }
                cursor = cursor.plusDays(1)
            }
        }

        if (newInstances.isNotEmpty()) {
            database.withTransaction {
                instanceDao.upsertAll(newInstances)
                if (newStates.isNotEmpty()) {
                    runtimeStateDao.upsertAll(newStates)
                }
                eventLogDao.upsertAll(newEvents)
            }
        }
    }

    suspend fun createTask(draft: TaskDraft): String {
        val localDate = LocalDate.parse(draft.localDate)
        return if (draft.saveAsRoutine) {
            val templateId = createTaskTemplateFromDraft(draft)
            createInstanceFromTemplate(templateId, localDate) ?: createStandaloneTaskInstance(draft, localDate)
        } else {
            createStandaloneTaskInstance(draft, localDate)
        }
    }

    suspend fun createTaskTemplateFromDraft(draft: TaskDraft): String {
        val localDate = LocalDate.parse(draft.localDate)
        val category = draft.categoryId?.let { categoryDao.getById(it) }
        val now = clock.nowEpochMillis()
        val templateId = idProvider.newId()
        val template = TaskTemplateEntity(
            id = templateId,
            name = draft.name.sanitizedName(),
            type = draft.type,
            defaultTargetDurationMillis = countdownTargetDuration(draft),
            preferredStartMinuteOfDay = parseMinuteOfDay(draft.preferredStartTime ?: draft.startTime),
            defaultStartMinuteOfDay = if (draft.type == TaskTypes.TIME_WINDOW) parseMinuteOfDay(draft.startTime) else null,
            defaultEndMinuteOfDay = if (draft.type == TaskTypes.TIME_WINDOW) parseMinuteOfDay(draft.endTime) else null,
            colorArgb = draft.colorArgb,
            categoryId = category?.id,
            projectName = draft.projectName?.trim()?.ifBlank { null },
            tagsCsv = draft.tags?.normalizedCsv(),
            note = draft.note?.trim()?.ifBlank { null },
            priority = sanitizePriority(draft.priority),
            anchorDate = localDate.toString(),
            repeatMode = draft.repeatMode,
            repeatDaysCsv = draft.repeatDaysCsv?.normalizedCsv(),
            repeatInterval = draft.repeatInterval.coerceAtLeast(1),
            remindersEnabled = draft.remindersEnabled,
            remindAtStart = draft.remindAtStart,
            remindBeforeEndMinutes = draft.remindBeforeEndMinutes?.coerceAtLeast(0),
            remindAtDeadline = draft.remindAtDeadline,
            countTowardGoals = draft.countTowardGoals,
            sessionMode = sanitizeSessionMode(draft.sessionMode, draft.type),
            pomodoroWorkMinutes = sanitizePomodoroMinutes(draft.pomodoroWorkMinutes),
            pomodoroBreakMinutes = sanitizePomodoroBreakMinutes(draft.pomodoroBreakMinutes),
            pomodoroCycles = sanitizePomodoroCycles(draft.pomodoroCycles),
            autoGenerateAheadDays = 7,
            archived = false,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        database.withTransaction {
            templateDao.upsert(template)
            insertEventLocked(template.id, template.id, TaskEventTypes.CREATE_TEMPLATE, now, clock.elapsedRealtimeMillis(), "{\"type\":\"${template.type}\"}")
        }
        return templateId
    }

    suspend fun createInstanceFromTemplate(templateId: String, localDate: LocalDate): String? {
        val template = templateDao.getById(templateId) ?: return null
        val existing = instanceDao.getByTemplateAndDate(templateId, localDate.toString())
        if (existing != null) return existing.id
        val categoryName = template.categoryId?.let { categoryDao.getById(it)?.name }
        val now = clock.nowEpochMillis()
        val instance = buildInstanceFromTemplate(
            template = template,
            localDate = localDate,
            categoryName = categoryName,
            nowEpoch = now,
            sortOrder = defaultSortOrder(
                priority = template.priority,
                minuteOfDay = template.preferredStartMinuteOfDay ?: template.defaultStartMinuteOfDay
            ),
            generated = false
        )
        database.withTransaction {
            instanceDao.upsert(instance)
            if (instance.type != TaskTypes.TIME_WINDOW) {
                runtimeStateDao.upsert(TaskRuntimeStateEntity.idle(instance.id, now))
            }
            insertEventLocked(instance.id, template.id, TaskEventTypes.CREATE_INSTANCE, now, clock.elapsedRealtimeMillis(), "{\"type\":\"${template.type}\"}")
        }
        return instance.id
    }

    suspend fun archiveTemplate(templateId: String) {
        database.withTransaction {
            val template = templateDao.getById(templateId) ?: return@withTransaction
            templateDao.upsert(template.copy(archived = true, updatedAtEpochMillis = clock.nowEpochMillis()))
            insertEventLocked(templateId, templateId, TaskEventTypes.ARCHIVE, clock.nowEpochMillis(), clock.elapsedRealtimeMillis(), "{\"level\":\"template\"}")
        }
    }

    suspend fun updateResultNote(instanceId: String, note: String) {
        database.withTransaction {
            val now = clock.nowEpochMillis()
            val instance = instanceDao.getById(instanceId) ?: return@withTransaction
            instanceDao.upsert(instance.copy(resultNote = note.trim().ifBlank { null }, updatedAtEpochMillis = now))
            insertEventLocked(instance.id, instance.templateId, TaskEventTypes.UPDATE_NOTE, now, clock.elapsedRealtimeMillis(), "{\"note\":true}")
        }
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
            if (instance.archived || instance.type == TaskTypes.TIME_WINDOW) return@withTransaction
            if (instance.status !in listOf(TaskStatuses.READY, TaskStatuses.PAUSED)) return@withTransaction
            val existing = runtimeStateDao.getByInstanceId(instanceId) ?: TaskRuntimeStateEntity.idle(instanceId, now)
            val accumulated = if (existing.status == TaskStatuses.PAUSED) existing.accumulatedMillis else existing.accumulatedMillis
            instanceDao.upsert(
                instance.copy(
                    status = TaskStatuses.RUNNING,
                    updatedAtEpochMillis = now,
                    completedAtEpochMillis = null,
                    completionSource = null,
                    cancelledAtEpochMillis = null,
                    missedAtEpochMillis = null,
                    missSource = null
                )
            )
            runtimeStateDao.upsert(
                existing.copy(
                    status = TaskStatuses.RUNNING,
                    accumulatedMillis = if (instance.type == TaskTypes.COUNT_DOWN) {
                        min(accumulated, resolvedCountdownTarget(instance) ?: Long.MAX_VALUE)
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
            val completed = instance.type == TaskTypes.COUNT_DOWN && (resolvedCountdownTarget(instance)?.let { newAccumulated >= it } == true)
            insertSessionForOpenSegmentLocked(
                instance = instance,
                state = state,
                nowEpochMillis = now,
                durationMillis = segment,
                source = if (completed) SessionSources.COUNTDOWN_AUTO else SessionSources.MANUAL
            )
            if (completed) {
                markInstanceCompletedLocked(instance, CompletionSources.COUNTDOWN_AUTO, now, nowElapsed)
                runtimeStateDao.upsert(state.completedCopy(resolvedCountdownTarget(instance) ?: newAccumulated, now))
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
            if (instance.type == TaskTypes.TIME_WINDOW && instance.plannedEndEpochMillis != null && now >= instance.plannedEndEpochMillis) {
                instanceDao.upsert(
                    instance.copy(
                        status = TaskStatuses.MISSED,
                        updatedAtEpochMillis = now,
                        missedAtEpochMillis = now,
                        missSource = MissSources.DEADLINE_AUTO
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

                if (instance.type == TaskTypes.COUNT_DOWN) {
                    val target = resolvedCountdownTarget(instance) ?: return@forEach
                    val needed = max(0L, target - state.accumulatedMillis)
                    if (wallDelta >= needed) {
                        insertSessionLocked(instance, startedAtEpoch, needed, SessionSources.RECOVERED_COMPLETED, now)
                        markInstanceCompletedLocked(instance, CompletionSources.RECOVERED_AUTO, now, nowElapsed)
                        runtimeStateDao.upsert(state.completedCopy(target, now))
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

    private suspend fun createStandaloneTaskInstance(draft: TaskDraft, localDate: LocalDate): String {
        val category = draft.categoryId?.let { categoryDao.getById(it) }
        val now = clock.nowEpochMillis()
        val instanceId = idProvider.newId()
        val preferredStartEpoch = parseMinuteOfDay(draft.preferredStartTime ?: if (draft.type != TaskTypes.TIME_WINDOW) draft.startTime else null)?.let {
            epochForMinute(localDate, it)
        }
        val plannedStart = if (draft.type == TaskTypes.TIME_WINDOW) {
            parseMinuteOfDay(draft.startTime)?.let { epochForMinute(localDate, it) }
        } else {
            preferredStartEpoch
        }
        val plannedEnd = if (draft.type == TaskTypes.TIME_WINDOW) {
            val startMinute = parseMinuteOfDay(draft.startTime)
            val endMinute = parseMinuteOfDay(draft.endTime)
            if (startMinute != null && endMinute != null) {
                val endDate = if (endMinute <= startMinute) localDate.plusDays(1) else localDate
                epochForMinute(endDate, endMinute)
            } else {
                null
            }
        } else {
            null
        }
        val sessionMode = sanitizeSessionMode(draft.sessionMode, draft.type)
        val tempInstance = TaskInstanceEntity(
            id = instanceId,
            templateId = null,
            localDate = localDate.toString(),
            nameSnapshot = draft.name.sanitizedName(),
            type = draft.type,
            status = TaskStatuses.READY,
            targetDurationMillis = countdownTargetDuration(draft),
            preferredStartEpochMillis = preferredStartEpoch,
            plannedStartEpochMillis = plannedStart,
            plannedEndEpochMillis = plannedEnd,
            colorArgb = draft.colorArgb,
            categoryIdSnapshot = category?.id,
            categoryNameSnapshot = category?.name,
            projectNameSnapshot = draft.projectName?.trim()?.ifBlank { null },
            tagsSnapshot = draft.tags?.normalizedCsv(),
            noteSnapshot = draft.note?.trim()?.ifBlank { null },
            priority = sanitizePriority(draft.priority),
            remindersEnabled = draft.remindersEnabled,
            remindAtStart = draft.remindAtStart,
            remindBeforeEndMinutes = draft.remindBeforeEndMinutes?.coerceAtLeast(0),
            remindAtDeadline = draft.remindAtDeadline,
            countTowardGoals = draft.countTowardGoals,
            sessionMode = sessionMode,
            pomodoroWorkMinutes = sanitizePomodoroMinutes(draft.pomodoroWorkMinutes),
            pomodoroBreakMinutes = sanitizePomodoroBreakMinutes(draft.pomodoroBreakMinutes),
            pomodoroCycles = sanitizePomodoroCycles(draft.pomodoroCycles),
            sortOrder = defaultSortOrder(
                priority = sanitizePriority(draft.priority),
                minuteOfDay = parseMinuteOfDay(draft.preferredStartTime ?: draft.startTime)
            ),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            completedAtEpochMillis = null,
            missedAtEpochMillis = null,
            cancelledAtEpochMillis = null,
            completionSource = null,
            missSource = null,
            resultNote = null,
            archived = false,
            archivedAtEpochMillis = null
        )
        val instance = tempInstance.copy(status = initialStatus(tempInstance, now))
        database.withTransaction {
            instanceDao.upsert(instance)
            if (instance.type != TaskTypes.TIME_WINDOW) {
                runtimeStateDao.upsert(TaskRuntimeStateEntity.idle(instance.id, now))
            }
            insertEventLocked(instance.id, null, TaskEventTypes.CREATE_INSTANCE, now, clock.elapsedRealtimeMillis(), "{\"type\":\"${instance.type}\"}")
        }
        return instance.id
    }

    private fun buildInstanceFromTemplate(
        template: TaskTemplateEntity,
        localDate: LocalDate,
        categoryName: String?,
        nowEpoch: Long,
        sortOrder: Int,
        generated: Boolean
    ): TaskInstanceEntity {
        val preferredStartEpoch = template.preferredStartMinuteOfDay?.let { epochForMinute(localDate, it) }
        val plannedStart = template.defaultStartMinuteOfDay?.let { epochForMinute(localDate, it) }
        val plannedEnd = if (template.type == TaskTypes.TIME_WINDOW && template.defaultStartMinuteOfDay != null && template.defaultEndMinuteOfDay != null) {
            val endDate = if (template.defaultEndMinuteOfDay <= template.defaultStartMinuteOfDay) localDate.plusDays(1) else localDate
            epochForMinute(endDate, template.defaultEndMinuteOfDay)
        } else {
            null
        }
        val base = TaskInstanceEntity(
            id = idProvider.newId(),
            templateId = template.id,
            localDate = localDate.toString(),
            nameSnapshot = template.name.sanitizedName(),
            type = template.type,
            status = TaskStatuses.READY,
            targetDurationMillis = if (template.type == TaskTypes.COUNT_DOWN) {
                if (template.sessionMode == SessionModes.POMODORO) {
                    tempPomodoroTarget(template)
                } else {
                    template.defaultTargetDurationMillis
                }
            } else {
                null
            },
            preferredStartEpochMillis = preferredStartEpoch,
            plannedStartEpochMillis = if (template.type == TaskTypes.TIME_WINDOW) plannedStart else preferredStartEpoch,
            plannedEndEpochMillis = plannedEnd,
            colorArgb = template.colorArgb,
            categoryIdSnapshot = template.categoryId,
            categoryNameSnapshot = categoryName,
            projectNameSnapshot = template.projectName,
            tagsSnapshot = template.tagsCsv,
            noteSnapshot = template.note,
            priority = sanitizePriority(template.priority),
            remindersEnabled = template.remindersEnabled,
            remindAtStart = template.remindAtStart,
            remindBeforeEndMinutes = template.remindBeforeEndMinutes,
            remindAtDeadline = template.remindAtDeadline,
            countTowardGoals = template.countTowardGoals,
            sessionMode = template.sessionMode,
            pomodoroWorkMinutes = template.pomodoroWorkMinutes,
            pomodoroBreakMinutes = template.pomodoroBreakMinutes,
            pomodoroCycles = template.pomodoroCycles,
            sortOrder = sortOrder,
            createdAtEpochMillis = nowEpoch,
            updatedAtEpochMillis = nowEpoch,
            completedAtEpochMillis = null,
            missedAtEpochMillis = null,
            cancelledAtEpochMillis = null,
            completionSource = null,
            missSource = null,
            resultNote = if (generated) null else null,
            archived = false,
            archivedAtEpochMillis = null
        )
        return base.copy(status = initialStatus(base, nowEpoch))
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
        val target = resolvedCountdownTarget(instance) ?: return null
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

    private fun initialStatus(instance: TaskInstanceEntity, now: Long): String = when (instance.type) {
        TaskTypes.TIME_WINDOW -> when {
            instance.plannedEndEpochMillis == null || instance.plannedStartEpochMillis == null -> TaskStatuses.PLANNED
            now >= instance.plannedEndEpochMillis -> TaskStatuses.MISSED
            now >= instance.plannedStartEpochMillis -> TaskStatuses.READY
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

    private fun parseMinuteOfDay(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: return null
        val minute = parts[1].toIntOrNull()?.coerceIn(0, 59) ?: return null
        return hour * 60 + minute
    }

    private fun tempPomodoroTarget(template: TaskTemplateEntity): Long? {
        val work = (template.pomodoroWorkMinutes ?: 25).coerceAtLeast(1) * 60_000L
        val breakMillis = (template.pomodoroBreakMinutes ?: 5).coerceAtLeast(0) * 60_000L
        val cycles = (template.pomodoroCycles ?: 4).coerceAtLeast(1)
        return work * cycles + breakMillis * max(0, cycles - 1)
    }

    private fun countdownTargetDuration(draft: TaskDraft): Long? {
        if (draft.type != TaskTypes.COUNT_DOWN) return null
        return if (sanitizeSessionMode(draft.sessionMode, draft.type) == SessionModes.POMODORO) {
            val work = (draft.pomodoroWorkMinutes ?: 25).coerceAtLeast(1) * 60_000L
            val breakMillis = (draft.pomodoroBreakMinutes ?: 5).coerceAtLeast(0) * 60_000L
            val cycles = (draft.pomodoroCycles ?: 4).coerceAtLeast(1)
            work * cycles + breakMillis * max(0, cycles - 1)
        } else {
            (draft.countdownMinutes ?: 25L).coerceAtLeast(1L) * 60_000L
        }
    }

    private fun resolvedCountdownTarget(instance: TaskInstanceEntity): Long? =
        if (instance.type == TaskTypes.COUNT_DOWN) {
            PomodoroMath.totalProgramMillis(instance) ?: instance.targetDurationMillis
        } else {
            null
        }

    private fun sanitizePriority(priority: String): String = when (priority) {
        TaskPriorities.LOW, TaskPriorities.HIGH -> priority
        else -> TaskPriorities.MEDIUM
    }

    private fun sanitizeSessionMode(mode: String, type: String): String {
        if (type != TaskTypes.COUNT_DOWN) return SessionModes.STANDARD
        return if (mode == SessionModes.POMODORO) SessionModes.POMODORO else SessionModes.STANDARD
    }

    private fun sanitizePomodoroMinutes(value: Int?): Int? = value?.coerceAtLeast(1)

    private fun sanitizePomodoroBreakMinutes(value: Int?): Int? = value?.coerceAtLeast(0)

    private fun sanitizePomodoroCycles(value: Int?): Int? = value?.coerceAtLeast(1)

    private fun defaultSortOrder(priority: String, minuteOfDay: Int?): Int {
        val priorityWeight = when (priority) {
            TaskPriorities.HIGH -> 30_000
            TaskPriorities.MEDIUM -> 20_000
            else -> 10_000
        }
        val minuteWeight = minuteOfDay?.let { 2_000 - it.coerceIn(0, 1_439) } ?: 500
        return priorityWeight + minuteWeight
    }

    private fun String.sanitizedName(): String = trim().ifBlank { untitledTaskName }

    private fun String.normalizedCsv(): String? = split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(",")

    private fun TaskRuntimeStateEntity.completedCopy(accumulated: Long, now: Long): TaskRuntimeStateEntity = copy(
        status = TaskStatuses.COMPLETED,
        accumulatedMillis = accumulated.coerceAtLeast(0L),
        startedAtEpochMillis = null,
        startedAtElapsedRealtimeMillis = null,
        lastPersistedAtEpochMillis = now,
        version = version + 1
    )
}
