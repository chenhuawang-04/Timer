package com.timer.app.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timer.app.AppContainer
import com.timer.app.R
import com.timer.app.data.AccentPalettes
import com.timer.app.data.AppBackupPayload
import com.timer.app.data.AppPreferencesSnapshot
import com.timer.app.data.BackupPayloadCodec
import com.timer.app.data.CategoryDraft
import com.timer.app.data.DashboardLayouts
import com.timer.app.data.EnergyModes
import com.timer.app.data.GoalDraft
import com.timer.app.data.GoalEntity
import com.timer.app.data.GoalMetricTypes
import com.timer.app.data.GoalScopes
import com.timer.app.data.RoomTimerRepository
import com.timer.app.data.SessionModes
import com.timer.app.data.TaskCategoryEntity
import com.timer.app.data.TaskDraft
import com.timer.app.data.TaskEventLogEntity
import com.timer.app.data.TaskEventTypes
import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskPriorities
import com.timer.app.data.TaskRuntimeStateEntity
import com.timer.app.data.TaskSortModes
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTemplateEntity
import com.timer.app.data.TaskTypes
import com.timer.app.data.ThemeModes
import com.timer.app.data.toPortableBackup
import com.timer.app.domain.DurationFormatter
import com.timer.app.domain.PomodoroMath
import com.timer.app.domain.PomodoroPhaseTypes
import com.timer.app.domain.StatsCalculator
import com.timer.app.domain.StatsSummary
import com.timer.app.domain.TimerMath
import com.timer.app.sync.CloudSyncResultCodes
import com.timer.app.sync.CloudSyncSettingsDraft
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ticker = flow {
    while (true) {
        emit(Unit)
        delay(1_000L)
    }
}

enum class AppTab {
    TODAY,
    ROUTINES,
    CALENDAR,
    INSIGHTS,
    SETTINGS
}

data class TaskUiModel(
    val instance: TaskInstanceEntity,
    val state: TaskRuntimeStateEntity?,
    val displayText: String,
    val statusText: String,
    val actionStatus: String,
    val progress: Float?,
    val scheduleText: String?,
    val metaText: String,
    val noteText: String?,
    val pomodoroText: String?,
    val isRunning: Boolean,
    val supportsFocusMode: Boolean
)

data class RoutineUiModel(
    val template: TaskTemplateEntity,
    val categoryName: String?,
    val scheduleText: String,
    val reminderText: String,
    val statsText: String,
    val todayInstanceId: String?
)

data class GoalProgressUiModel(
    val goal: GoalEntity,
    val progress: Float,
    val progressText: String
)

data class CalendarDayUiModel(
    val date: LocalDate,
    val trackedMillis: Long,
    val plannedCount: Int,
    val completedCount: Int,
    val missedCount: Int,
    val score: Int,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean
)

data class HistoryUiModel(
    val instanceId: String,
    val title: String,
    val localDate: String,
    val statusText: String,
    val trackedText: String,
    val resultNote: String?,
    val categoryName: String?,
    val projectName: String?
)

data class AuditUiModel(
    val id: String,
    val title: String,
    val detail: String
)

data class SuggestionUiModel(
    val title: String,
    val body: String
)

data class TimerUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedMonth: YearMonth = YearMonth.now(),
    val activeTab: AppTab = AppTab.TODAY,
    val tasks: List<TaskUiModel> = emptyList(),
    val routines: List<RoutineUiModel> = emptyList(),
    val categories: List<TaskCategoryEntity> = emptyList(),
    val goals: List<GoalProgressUiModel> = emptyList(),
    val calendarDays: List<CalendarDayUiModel> = emptyList(),
    val stats: StatsSummary = StatsCalculator.empty(),
    val history: List<HistoryUiModel> = emptyList(),
    val audits: List<AuditUiModel> = emptyList(),
    val suggestions: List<SuggestionUiModel> = emptyList(),
    val focusTask: TaskUiModel? = null,
    val runningTaskCount: Int = 0,
    val appearance: AppPreferencesSnapshot = AppPreferencesSnapshot(),
    val cloudSync: CloudSyncSettingsUiState = CloudSyncSettingsUiState(),
    val notificationPermissionGranted: Boolean = true,
    val serviceWarningMessage: String? = null,
    val statusMessage: String? = null,
    val isLoading: Boolean = true
)

private data class UiControlState(
    val date: LocalDate,
    val tab: AppTab,
    val focusInstanceId: String?,
    val notificationPermissionGranted: Boolean,
    val serviceWarningMessage: String?,
    val statusMessage: String?
)

private data class UiRenderInputs(
    val snapshot: com.timer.app.data.AppSnapshot,
    val preferences: AppPreferencesSnapshot,
    val credentialState: com.timer.app.sync.CloudSyncCredentialSnapshot,
    val syncInProgress: Boolean,
    val controlState: UiControlState
)

class TimerViewModel(
    private val appContext: Context,
    private val container: AppContainer
) : ViewModel() {
    private val repository: RoomTimerRepository = container.repository
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val activeTab = MutableStateFlow(AppTab.TODAY)
    private val focusInstanceId = MutableStateFlow<String?>(null)
    private val notificationPermissionGranted = MutableStateFlow(true)
    private val serviceWarningMessage = MutableStateFlow<String?>(null)
    private val statusMessage = MutableStateFlow<String?>(null)

    private data class ControlPrimary(
        val date: LocalDate,
        val tab: AppTab,
        val focusInstanceId: String?
    )

    private data class ControlSecondary(
        val notificationPermissionGranted: Boolean,
        val serviceWarningMessage: String?,
        val statusMessage: String?
    )

    private val controls = run {
        val primary = combine(selectedDate, activeTab, focusInstanceId) { date, tab, focusId ->
            ControlPrimary(date, tab, focusId)
        }
        val secondary = combine(notificationPermissionGranted, serviceWarningMessage, statusMessage) { granted, warning, message ->
            ControlSecondary(granted, warning, message)
        }
        combine(primary, secondary) { first, second ->
            UiControlState(
                date = first.date,
                tab = first.tab,
                focusInstanceId = first.focusInstanceId,
                notificationPermissionGranted = second.notificationPermissionGranted,
                serviceWarningMessage = second.serviceWarningMessage,
                statusMessage = second.statusMessage
            )
        }
    }

    private val uiRenderInputs = combine(
        repository.observeAppSnapshot(),
        container.preferencesRepository.preferences,
        container.cloudSyncCoordinator.credentialState,
        container.cloudSyncCoordinator.syncInProgress,
        controls
    ) { snapshot, preferences, credentialState, syncInProgress, controlState ->
        UiRenderInputs(
            snapshot = snapshot,
            preferences = preferences,
            credentialState = credentialState,
            syncInProgress = syncInProgress,
            controlState = controlState
        )
    }

    val uiState = combine(uiRenderInputs, ticker) { inputs, _ ->
        buildUiState(
            snapshot = inputs.snapshot,
            preferences = inputs.preferences,
            credentialState = inputs.credentialState,
            syncInProgress = inputs.syncInProgress,
            controlState = inputs.controlState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TimerUiState(isLoading = true)
    )

    init {
        viewModelScope.launch {
            container.automationCoordinator.warmUp()
        }
    }

    fun setNotificationPermissionGranted(granted: Boolean) {
        notificationPermissionGranted.value = granted
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    fun selectTab(tab: AppTab) {
        activeTab.value = tab
        viewModelScope.launch {
            container.preferencesRepository.updateLastSelectedTab(tab.name)
        }
    }

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
        viewModelScope.launch {
            container.automationCoordinator.warmUp(date)
        }
    }

    fun changeMonth(delta: Long) {
        val shifted = selectedDate.value.plusMonths(delta)
        selectDate(shifted.withDayOfMonth(1))
    }

    fun openFocus(instanceId: String?) {
        focusInstanceId.value = instanceId
    }

    fun closeFocus() {
        focusInstanceId.value = null
    }

    fun createTask(draft: TaskDraft) {
        viewModelScope.launch {
            repository.createTask(draft)
            container.automationCoordinator.afterMutation(LocalDate.parse(draft.localDate))
            statusMessage.value = appContext.getString(R.string.status_message_task_saved)
        }
    }

    fun createCategory(draft: CategoryDraft) {
        viewModelScope.launch {
            repository.createCategory(draft)
            container.automationCoordinator.afterMutation(selectedDate.value)
            statusMessage.value = appContext.getString(R.string.status_message_category_saved)
        }
    }

    fun createGoal(draft: GoalDraft) {
        viewModelScope.launch {
            repository.createGoal(draft)
            container.automationCoordinator.afterMutation(selectedDate.value)
            statusMessage.value = appContext.getString(R.string.status_message_goal_saved)
        }
    }

    fun createTodayFromRoutine(templateId: String) {
        viewModelScope.launch {
            repository.createInstanceFromTemplate(templateId, selectedDate.value)
            container.automationCoordinator.afterMutation(selectedDate.value)
            statusMessage.value = appContext.getString(R.string.status_message_task_saved)
        }
    }

    fun archiveRoutine(templateId: String) {
        viewModelScope.launch {
            val result = repository.archiveTemplate(templateId)
            result.cancelledInstanceIds.forEach(container.deadlineAlarmScheduler::cancelFor)
            container.automationCoordinator.afterMutation(selectedDate.value)
            statusMessage.value = appContext.getString(
                R.string.status_message_routine_cancelled,
                result.cancelledInstanceIds.size
            )
        }
    }

    fun startTask(instanceId: String) {
        viewModelScope.launch {
            repository.startInstance(instanceId)
            container.automationCoordinator.afterMutation(selectedDate.value)
            handleServiceStartResult(com.timer.app.service.TimerForegroundService.start(appContext))
        }
    }

    fun pauseTask(instanceId: String) {
        viewModelScope.launch {
            repository.pauseInstance(instanceId)
            container.automationCoordinator.afterMutation(selectedDate.value)
        }
    }

    fun resumeTask(instanceId: String) {
        viewModelScope.launch {
            repository.resumeInstance(instanceId)
            container.automationCoordinator.afterMutation(selectedDate.value)
            handleServiceStartResult(com.timer.app.service.TimerForegroundService.start(appContext))
        }
    }

    fun completeTask(instanceId: String) {
        viewModelScope.launch {
            repository.completeInstanceManually(instanceId)
            container.deadlineAlarmScheduler.cancelFor(instanceId)
            container.automationCoordinator.afterMutation(selectedDate.value)
        }
    }

    fun cancelTask(instanceId: String) {
        viewModelScope.launch {
            repository.cancelInstance(instanceId)
            container.deadlineAlarmScheduler.cancelFor(instanceId)
            container.automationCoordinator.afterMutation(selectedDate.value)
        }
    }

    fun archiveTask(instanceId: String) {
        viewModelScope.launch {
            repository.archiveInstance(instanceId)
            container.deadlineAlarmScheduler.cancelFor(instanceId)
            container.automationCoordinator.afterMutation(selectedDate.value)
        }
    }

    fun saveResultNote(instanceId: String, note: String) {
        viewModelScope.launch {
            repository.updateResultNote(instanceId, note)
            container.automationCoordinator.afterMutation(selectedDate.value)
            statusMessage.value = appContext.getString(R.string.status_message_note_saved)
        }
    }

    fun updateThemeMode(value: String) {
        viewModelScope.launch { container.preferencesRepository.updateThemeMode(value) }
    }

    fun updateAccentPalette(value: String) {
        viewModelScope.launch { container.preferencesRepository.updateAccentPalette(value) }
    }

    fun updateDynamicColor(value: Boolean) {
        viewModelScope.launch { container.preferencesRepository.updateDynamicColor(value) }
    }

    fun updateDashboardLayout(value: String) {
        viewModelScope.launch { container.preferencesRepository.updateDashboardLayout(value) }
    }

    fun updateSortMode(value: String) {
        viewModelScope.launch { container.preferencesRepository.updateSortMode(value) }
    }

    fun updateShowCompletedTasks(value: Boolean) {
        viewModelScope.launch { container.preferencesRepository.updateShowCompletedTasks(value) }
    }

    fun updateEnergyMode(value: String) {
        viewModelScope.launch { container.preferencesRepository.updateEnergyMode(value) }
    }

    fun updateKeepScreenOnInFocus(value: Boolean) {
        viewModelScope.launch { container.preferencesRepository.updateKeepScreenOnInFocus(value) }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val repositoryData = repository.exportData()
                    val preferences = container.preferencesRepository.preferences.first().toPortableBackup()
                    val payloadText = BackupPayloadCodec.encode(
                        preferences = preferences,
                        repository = repositoryData,
                        exportedAtEpochMillis = System.currentTimeMillis()
                    )
                    appContext.contentResolver.openOutputStream(uri)?.use { output ->
                        OutputStreamWriter(output).use { writer -> writer.write(payloadText) }
                    }
                    container.preferencesRepository.updateLastBackupAt(System.currentTimeMillis())
                }
                statusMessage.value = appContext.getString(R.string.status_message_backup_exported)
            } catch (_: Throwable) {
                statusMessage.value = appContext.getString(R.string.status_message_backup_failed)
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input)).readText()
                    }.orEmpty()
                    val payload: AppBackupPayload = BackupPayloadCodec.decode(text)
                    repository.importData(payload.repository)
                    container.preferencesRepository.importSnapshot(
                        snapshot = payload.preferences,
                        importCloudSyncConfiguration = payload.containsCloudSyncConfiguration
                    )
                    container.cloudSyncCoordinator.refreshScheduleFromStoredPreferences()
                }
                container.automationCoordinator.warmUp(selectedDate.value)
                statusMessage.value = appContext.getString(R.string.status_message_backup_imported)
            } catch (_: Throwable) {
                statusMessage.value = appContext.getString(R.string.status_message_backup_import_failed)
            }
        }
    }

    fun saveCloudSyncSettings(draft: CloudSyncSettingsDraft) {
        viewModelScope.launch {
            container.cloudSyncCoordinator.saveSettings(draft)
            statusMessage.value = appContext.getString(R.string.cloud_sync_settings_saved)
        }
    }

    fun clearCloudSyncToken() {
        viewModelScope.launch {
            container.cloudSyncCoordinator.clearAccessToken()
            statusMessage.value = appContext.getString(R.string.cloud_sync_token_cleared)
        }
    }

    fun syncCloudNow() {
        viewModelScope.launch {
            val result = container.cloudSyncCoordinator.syncNow()
            statusMessage.value = result.message
        }
    }

    fun restoreCloudLatest() {
        viewModelScope.launch {
            val result = container.cloudSyncCoordinator.restoreLatest()
            statusMessage.value = result.message
            if (result.resultCode == CloudSyncResultCodes.RESTORED) {
                container.automationCoordinator.warmUp(selectedDate.value)
            }
        }
    }

    private fun handleServiceStartResult(result: com.timer.app.service.TimerForegroundServiceStartResult) {
        serviceWarningMessage.value = when (result) {
            com.timer.app.service.TimerForegroundServiceStartResult.Started -> null
            is com.timer.app.service.TimerForegroundServiceStartResult.Failed -> appContext.getString(
                R.string.background_service_start_failed,
                result.reason
            )
        }
    }

    private fun buildUiState(
        snapshot: com.timer.app.data.AppSnapshot,
        preferences: AppPreferencesSnapshot,
        credentialState: com.timer.app.sync.CloudSyncCredentialSnapshot,
        syncInProgress: Boolean,
        controlState: UiControlState
    ): TimerUiState {
        val nowEpoch = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val selectedDateString = controlState.date.toString()
        val statesByInstance = snapshot.states.associateBy { it.instanceId }
        val trackedByInstance = snapshot.sessions.groupBy { it.instanceId }.mapValues { (_, sessions) ->
            sessions.sumOf { it.durationMillis }
        }.toMutableMap()
        snapshot.states.filter { it.status == TaskStatuses.RUNNING }.forEach { state ->
            val instance = snapshot.instances.firstOrNull { it.id == state.instanceId } ?: return@forEach
            val extra = if (instance.type == TaskTypes.COUNT_DOWN) {
                TimerMath.clampCountdownSegment(instance, state, TimerMath.currentOpenSegmentMillis(state, nowElapsed))
            } else {
                TimerMath.currentOpenSegmentMillis(state, nowElapsed)
            }
            trackedByInstance[state.instanceId] = (trackedByInstance[state.instanceId] ?: 0L) + extra
        }

        val stats = StatsCalculator.calculate(
            instances = snapshot.instances,
            states = snapshot.states,
            sessions = snapshot.sessions,
            nowEpochMillis = nowEpoch,
            nowElapsedRealtimeMillis = nowElapsed,
            referenceDate = controlState.date
        )

        val selectedInstances = snapshot.instances
            .filter { it.localDate == selectedDateString && !it.archived }
            .filter { preferences.showCompletedTasks || it.status !in setOf(TaskStatuses.COMPLETED, TaskStatuses.CANCELLED) }

        val taskModels = selectedInstances
            .map { instance -> instance.toTaskUiModel(statesByInstance[instance.id], nowEpoch, nowElapsed) }
            .sortedWith(taskComparator(preferences.sortMode))

        val templatesById = snapshot.templates.associateBy { it.id }
        val routineModels = snapshot.templates
            .map { template ->
                val todayInstance = snapshot.instances.firstOrNull { it.templateId == template.id && it.localDate == selectedDateString && !it.archived }
                val categoryName = snapshot.categories.firstOrNull { it.id == template.categoryId }?.name
                RoutineUiModel(
                    template = template,
                    categoryName = categoryName,
                    scheduleText = scheduleTextForTemplate(template),
                    reminderText = reminderTextForTemplate(template),
                    statsText = appContext.getString(
                        R.string.routine_stats_format,
                        snapshot.instances.count { it.templateId == template.id && it.status == TaskStatuses.COMPLETED },
                        snapshot.instances.count { it.templateId == template.id && it.status != TaskStatuses.CANCELLED }
                    ),
                    todayInstanceId = todayInstance?.id
                )
            }
            .sortedByDescending { it.template.updatedAtEpochMillis }

        val goalModels = snapshot.goals.map { goal ->
            val value = goalCurrentValue(goal, snapshot.instances, snapshot.states, snapshot.sessions, controlState.date)
            val target = goal.targetValue.coerceAtLeast(1L)
            GoalProgressUiModel(
                goal = goal,
                progress = (value.toFloat() / target.toFloat()).coerceIn(0f, 1f),
                progressText = when (goal.metricType) {
                    GoalMetricTypes.TIME_WINDOW_COMPLETION_RATE -> appContext.getString(R.string.goal_progress_percent, value, target)
                    else -> appContext.getString(R.string.goal_progress_generic, value, target)
                }
            )
        }

        val month = YearMonth.from(controlState.date)
        val calendarDays = buildCalendarDays(month, controlState.date, snapshot.instances, snapshot.sessions, snapshot.states)
        val today = Instant.ofEpochMilli(nowEpoch).atZone(ZoneId.systemDefault()).toLocalDate()

        val history = snapshot.instances
            .filter { instance ->
                instance.isMeaningfulHistoryEntry(
                    referenceDate = today,
                    trackedMillis = trackedByInstance[instance.id] ?: 0L
                )
            }
            .sortedByDescending { it.updatedAtEpochMillis }
            .take(30)
            .map { instance ->
                HistoryUiModel(
                    instanceId = instance.id,
                    title = instance.nameSnapshot,
                    localDate = instance.localDate,
                    statusText = statusText(instance.status),
                    trackedText = DurationFormatter.compact(trackedByInstance[instance.id] ?: 0L),
                    resultNote = instance.resultNote,
                    categoryName = instance.categoryNameSnapshot,
                    projectName = instance.projectNameSnapshot
                )
            }

        val instanceNames = snapshot.instances.associateBy({ it.id }, { it.nameSnapshot }) +
            snapshot.templates.associateBy({ it.id }, { it.name })
        val audits = snapshot.events
            .filter { it.eventType != TaskEventTypes.GENERATE_INSTANCE }
            .sortedByDescending { it.atEpochMillis }
            .take(80)
            .map { event ->
                AuditUiModel(
                    id = event.id,
                    title = eventTitle(event, instanceNames),
                    detail = eventDetail(event)
                )
            }

        val suggestions = buildSuggestions(stats, selectedInstances)
        val runningTasks = taskModels.filter { it.isRunning }
        val focusTask = taskModels.firstOrNull {
            it.instance.id == controlState.focusInstanceId && it.supportsFocusMode
        }

        return TimerUiState(
            selectedDate = controlState.date,
            selectedMonth = month,
            activeTab = controlState.tab,
            tasks = taskModels,
            routines = routineModels,
            categories = snapshot.categories,
            goals = goalModels,
            calendarDays = calendarDays,
            stats = stats,
            history = history,
            audits = audits,
            suggestions = suggestions,
            focusTask = focusTask,
            runningTaskCount = runningTasks.size,
            appearance = preferences,
            cloudSync = CloudSyncSettingsUiState(
                preferences = preferences.cloudSync,
                hasToken = credentialState.hasToken,
                isBusy = syncInProgress
            ),
            notificationPermissionGranted = controlState.notificationPermissionGranted,
            serviceWarningMessage = controlState.serviceWarningMessage,
            statusMessage = controlState.statusMessage,
            isLoading = false
        )
    }

    private fun TaskInstanceEntity.isMeaningfulHistoryEntry(
        referenceDate: LocalDate,
        trackedMillis: Long
    ): Boolean {
        if (archived) return false
        val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return false
        if (date.isAfter(referenceDate)) return false
        val hasOutcome = status in setOf(
            TaskStatuses.COMPLETED,
            TaskStatuses.MISSED,
            TaskStatuses.CANCELLED
        )
        val hasUserSignal = trackedMillis > 0L || !resultNote.isNullOrBlank()
        return hasOutcome || hasUserSignal
    }

    private fun buildCalendarDays(
        month: YearMonth,
        selectedDate: LocalDate,
        instances: List<TaskInstanceEntity>,
        sessions: List<com.timer.app.data.TaskSessionEntity>,
        states: List<TaskRuntimeStateEntity>
    ): List<CalendarDayUiModel> {
        val firstOfMonth = month.atDay(1)
        val start = firstOfMonth.minusDays(((firstOfMonth.dayOfWeek.value + 6) % 7).toLong())
        return (0 until 42).map { offset ->
            val date = start.plusDays(offset.toLong())
            val dateString = date.toString()
            val dayInstances = instances.filter { it.localDate == dateString }
            val trackedMillis = sessions.filter {
                Instant.ofEpochMilli(it.startedAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate() == date
            }.sumOf { it.durationMillis }
            val dayWindow = dayInstances.filter { it.type == TaskTypes.TIME_WINDOW }
            val windowCompleted = dayWindow.count { it.status == TaskStatuses.COMPLETED }
            val windowMissed = dayWindow.count { it.status == TaskStatuses.MISSED }
            val windowRate = if (windowCompleted + windowMissed == 0) 0f else windowCompleted.toFloat() / (windowCompleted + windowMissed).toFloat()
            CalendarDayUiModel(
                date = date,
                trackedMillis = trackedMillis,
                plannedCount = dayInstances.count { it.status != TaskStatuses.CANCELLED },
                completedCount = dayInstances.count { it.status == TaskStatuses.COMPLETED },
                missedCount = dayInstances.count { it.status == TaskStatuses.MISSED },
                score = statsDayScore(trackedMillis, dayInstances, windowRate),
                isCurrentMonth = date.month == month.month,
                isToday = date == LocalDate.now(),
                isSelected = date == selectedDate
            )
        }
    }

    private fun statsDayScore(trackedMillis: Long, dayInstances: List<TaskInstanceEntity>, windowRate: Float): Int {
        val planned = dayInstances.count { it.status != TaskStatuses.CANCELLED }
        val completed = dayInstances.count { it.status == TaskStatuses.COMPLETED }
        val completionScore = if (planned == 0) 0f else completed.toFloat() / planned.toFloat()
        return ((completionScore * 60f) + ((trackedMillis / 60_000f / 60f).coerceIn(0f, 1f) * 25f) + (windowRate * 15f)).toInt().coerceIn(0, 100)
    }

    private fun goalCurrentValue(
        goal: GoalEntity,
        instances: List<TaskInstanceEntity>,
        states: List<TaskRuntimeStateEntity>,
        sessions: List<com.timer.app.data.TaskSessionEntity>,
        date: LocalDate
    ): Long {
        val filtered = instances.filter { instance ->
            (goal.categoryId == null || goal.categoryId == instance.categoryIdSnapshot) &&
                (goal.projectName.isNullOrBlank() || goal.projectName == instance.projectNameSnapshot)
        }
        return when (goal.metricType) {
            GoalMetricTypes.COMPLETED_TASKS -> when (goal.scope) {
                GoalScopes.DAILY -> filtered.count { it.localDate == date.toString() && it.status == TaskStatuses.COMPLETED }.toLong()
                GoalScopes.WEEKLY -> filtered.count {
                    val localDate = LocalDate.parse(it.localDate)
                    !localDate.isBefore(date.minusDays(6)) && !localDate.isAfter(date) && it.status == TaskStatuses.COMPLETED
                }.toLong()
                else -> filtered.count {
                    val localDate = LocalDate.parse(it.localDate)
                    localDate.month == date.month && localDate.year == date.year && it.status == TaskStatuses.COMPLETED
                }.toLong()
            }
            GoalMetricTypes.TRACKED_MINUTES -> {
                val instanceIds = filtered.map { it.id }.toSet()
                val total = sessions.filter {
                    it.instanceId in instanceIds && when (goal.scope) {
                        GoalScopes.DAILY -> Instant.ofEpochMilli(it.startedAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate() == date
                        GoalScopes.WEEKLY -> {
                            val sessionDate = Instant.ofEpochMilli(it.startedAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                            !sessionDate.isBefore(date.minusDays(6)) && !sessionDate.isAfter(date)
                        }
                        else -> {
                            val sessionDate = Instant.ofEpochMilli(it.startedAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                            sessionDate.month == date.month && sessionDate.year == date.year
                        }
                    }
                }.sumOf { it.durationMillis } / 60_000L
                total
            }
            GoalMetricTypes.TIME_WINDOW_COMPLETION_RATE -> {
                val windows = filtered.filter { it.type == TaskTypes.TIME_WINDOW }
                val denominator = windows.count { it.status in setOf(TaskStatuses.COMPLETED, TaskStatuses.MISSED) }
                val completed = windows.count { it.status == TaskStatuses.COMPLETED }
                if (denominator == 0) 0L else ((completed.toFloat() / denominator.toFloat()) * 100f).toLong()
            }
            else -> 0L
        }
    }

    private fun TaskInstanceEntity.toTaskUiModel(state: TaskRuntimeStateEntity?, nowEpoch: Long, nowElapsed: Long): TaskUiModel {
        val display = if (type == TaskTypes.TIME_WINDOW) 0L else TimerMath.displayMillis(this, state, nowElapsed)
        val progress = if (type == TaskTypes.COUNT_DOWN) {
            val target = PomodoroMath.totalProgramMillis(this) ?: targetDurationMillis
            if (target != null && target > 0L) {
                val remaining = TimerMath.remainingMillis(this, state, nowElapsed) ?: target
                1f - (remaining.toFloat() / target.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
        } else {
            null
        }
        val actionStatus = if (type == TaskTypes.TIME_WINDOW && status == TaskStatuses.PLANNED && plannedStartEpochMillis != null && plannedEndEpochMillis != null) {
            if (nowEpoch in plannedStartEpochMillis until plannedEndEpochMillis) TaskStatuses.READY else status
        } else {
            status
        }
        val pomodoroPhase = PomodoroMath.phaseFor(this, state, nowElapsed)
        val pomodoroText = pomodoroPhase?.takeIf { it.phaseType != PomodoroPhaseTypes.DONE }?.let { phase ->
            appContext.getString(
                R.string.pomodoro_summary,
                phase.cycleNumber,
                phase.totalCycles,
                if (phase.phaseType == PomodoroPhaseTypes.WORK) {
                    appContext.getString(R.string.pomodoro_phase_work)
                } else {
                    appContext.getString(R.string.pomodoro_phase_break)
                },
                DurationFormatter.clock(phase.phaseRemainingMillis)
            )
        }
        return TaskUiModel(
            instance = this,
            state = state,
            displayText = if (type == TaskTypes.TIME_WINDOW) "" else DurationFormatter.clock(display),
            statusText = statusText(actionStatus),
            actionStatus = actionStatus,
            progress = progress,
            scheduleText = scheduleText(this),
            metaText = listOfNotNull(
                categoryNameSnapshot,
                projectNameSnapshot,
                priorityLabel(priority),
                tagsSnapshot
            ).joinToString(" · "),
            noteText = noteSnapshot,
            pomodoroText = pomodoroText,
            isRunning = actionStatus == TaskStatuses.RUNNING,
            supportsFocusMode = when (type) {
                TaskTypes.TIME_WINDOW -> actionStatus == TaskStatuses.READY
                TaskTypes.COUNT_UP, TaskTypes.COUNT_DOWN -> actionStatus in setOf(
                    TaskStatuses.READY,
                    TaskStatuses.RUNNING,
                    TaskStatuses.PAUSED
                )
                else -> false
            }
        )
    }

    private fun scheduleText(instance: TaskInstanceEntity): String? {
        val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        return when (instance.type) {
            TaskTypes.TIME_WINDOW -> {
                val start = instance.plannedStartEpochMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().format(formatter) }
                val end = instance.plannedEndEpochMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().format(formatter) }
                if (start != null && end != null) "$start - $end" else null
            }
            else -> instance.preferredStartEpochMillis?.let {
                appContext.getString(
                    R.string.task_meta_planned_time,
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().format(formatter)
                )
            }
        }
    }

    private fun scheduleTextForTemplate(template: TaskTemplateEntity): String {
        val repeatText = when (template.repeatMode) {
            com.timer.app.data.RepeatModes.DAILY -> appContext.getString(R.string.repeat_daily)
            com.timer.app.data.RepeatModes.WEEKLY -> appContext.getString(R.string.repeat_weekly)
            com.timer.app.data.RepeatModes.WEEKDAYS -> appContext.getString(R.string.repeat_weekdays)
            com.timer.app.data.RepeatModes.MONTHLY -> appContext.getString(R.string.repeat_monthly)
            com.timer.app.data.RepeatModes.CUSTOM_DAYS -> appContext.getString(R.string.repeat_custom)
            else -> appContext.getString(R.string.repeat_once)
        }
        val timeText = template.preferredStartMinuteOfDay?.let { minute ->
            String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)
        } ?: template.defaultStartMinuteOfDay?.let { minute ->
            String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)
        }
        return if (timeText == null) repeatText else "$repeatText · $timeText"
    }

    private fun reminderTextForTemplate(template: TaskTemplateEntity): String {
        if (!template.remindersEnabled) return appContext.getString(R.string.reminder_disabled)
        val parts = mutableListOf<String>()
        if (template.remindAtStart) parts += appContext.getString(R.string.reminder_start)
        if ((template.remindBeforeEndMinutes ?: 0) > 0) parts += appContext.getString(R.string.reminder_before_end, template.remindBeforeEndMinutes ?: 0)
        if (template.remindAtDeadline) parts += appContext.getString(R.string.reminder_deadline)
        return parts.joinToString(" · ").ifBlank { appContext.getString(R.string.reminder_enabled) }
    }

    private fun eventTitle(event: TaskEventLogEntity, names: Map<String, String>): String {
        val subject = names[event.instanceId] ?: names[event.templateId] ?: appContext.getString(R.string.audit_unknown_subject)
        return when (event.eventType) {
            com.timer.app.data.TaskEventTypes.CREATE_TEMPLATE -> appContext.getString(R.string.audit_create_template, subject)
            com.timer.app.data.TaskEventTypes.CREATE_INSTANCE, com.timer.app.data.TaskEventTypes.GENERATE_INSTANCE -> appContext.getString(R.string.audit_create_instance, subject)
            com.timer.app.data.TaskEventTypes.START -> appContext.getString(R.string.audit_start, subject)
            com.timer.app.data.TaskEventTypes.PAUSE -> appContext.getString(R.string.audit_pause, subject)
            com.timer.app.data.TaskEventTypes.RESUME -> appContext.getString(R.string.audit_resume, subject)
            com.timer.app.data.TaskEventTypes.COMPLETE -> appContext.getString(R.string.audit_complete, subject)
            com.timer.app.data.TaskEventTypes.MISS -> appContext.getString(R.string.audit_miss, subject)
            com.timer.app.data.TaskEventTypes.CANCEL -> appContext.getString(R.string.audit_cancel, subject)
            com.timer.app.data.TaskEventTypes.RECOVER -> appContext.getString(R.string.audit_recover, subject)
            com.timer.app.data.TaskEventTypes.ARCHIVE -> appContext.getString(R.string.audit_archive, subject)
            com.timer.app.data.TaskEventTypes.UPDATE_NOTE -> appContext.getString(R.string.audit_note, subject)
            else -> subject
        }
    }

    private fun eventDetail(event: TaskEventLogEntity): String {
        val time = Instant.ofEpochMilli(event.atEpochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault()))
        return listOfNotNull(time, event.payloadJson).joinToString(" · ")
    }

    private fun buildSuggestions(stats: StatsSummary, selectedInstances: List<TaskInstanceEntity>): List<SuggestionUiModel> {
        val suggestions = mutableListOf<SuggestionUiModel>()
        if (selectedInstances.isEmpty()) {
            suggestions += SuggestionUiModel(
                appContext.getString(R.string.suggestion_seed_title),
                appContext.getString(R.string.suggestion_seed_body)
            )
        }
        if (stats.missedTodayCount > 0) {
            suggestions += SuggestionUiModel(
                appContext.getString(R.string.suggestion_miss_title),
                appContext.getString(R.string.suggestion_miss_body)
            )
        }
        if (stats.currentStreakDays >= 3) {
            suggestions += SuggestionUiModel(
                appContext.getString(R.string.suggestion_streak_title, stats.currentStreakDays),
                appContext.getString(R.string.suggestion_streak_body)
            )
        }
        if (selectedInstances.count { it.priority == TaskPriorities.HIGH && it.status != TaskStatuses.COMPLETED } >= 2) {
            suggestions += SuggestionUiModel(
                appContext.getString(R.string.suggestion_focus_title),
                appContext.getString(R.string.suggestion_focus_body)
            )
        }
        if (selectedInstances.any { it.type == TaskTypes.COUNT_DOWN && it.sessionMode == SessionModes.POMODORO }) {
            suggestions += SuggestionUiModel(
                appContext.getString(R.string.suggestion_pomodoro_title),
                appContext.getString(R.string.suggestion_pomodoro_body)
            )
        }
        return suggestions.take(4)
    }

    private fun statusText(status: String): String = when (status) {
        TaskStatuses.PLANNED -> appContext.getString(R.string.status_planned)
        TaskStatuses.READY -> appContext.getString(R.string.status_ready)
        TaskStatuses.RUNNING -> appContext.getString(R.string.status_running)
        TaskStatuses.PAUSED -> appContext.getString(R.string.status_paused)
        TaskStatuses.COMPLETED -> appContext.getString(R.string.status_completed)
        TaskStatuses.MISSED -> appContext.getString(R.string.status_missed)
        TaskStatuses.CANCELLED -> appContext.getString(R.string.status_cancelled)
        else -> appContext.getString(R.string.status_unknown)
    }

    private fun priorityLabel(priority: String): String = when (priority) {
        TaskPriorities.HIGH -> appContext.getString(R.string.priority_high)
        TaskPriorities.LOW -> appContext.getString(R.string.priority_low)
        else -> appContext.getString(R.string.priority_medium)
    }

    private fun taskComparator(sortMode: String): Comparator<TaskUiModel> = when (sortMode) {
        TaskSortModes.PRIORITY -> compareByDescending<TaskUiModel> { priorityRank(it.instance.priority) }
            .thenBy { it.instance.plannedStartEpochMillis ?: it.instance.preferredStartEpochMillis ?: Long.MAX_VALUE }
        TaskSortModes.START_TIME -> compareBy<TaskUiModel> { it.instance.plannedStartEpochMillis ?: it.instance.preferredStartEpochMillis ?: Long.MAX_VALUE }
            .thenByDescending { priorityRank(it.instance.priority) }
        TaskSortModes.CREATED_AT -> compareByDescending<TaskUiModel> { it.instance.createdAtEpochMillis }
        else -> compareByDescending<TaskUiModel> { priorityRank(it.instance.priority) }
            .thenByDescending { it.instance.status == TaskStatuses.RUNNING }
            .thenBy { it.instance.plannedStartEpochMillis ?: it.instance.preferredStartEpochMillis ?: Long.MAX_VALUE }
    }

    private fun priorityRank(priority: String): Int = when (priority) {
        TaskPriorities.HIGH -> 3
        TaskPriorities.MEDIUM -> 2
        else -> 1
    }
}

class TimerViewModelFactory(
    private val appContext: Context,
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TimerViewModel::class.java)) {
            return TimerViewModel(appContext.applicationContext, container) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
