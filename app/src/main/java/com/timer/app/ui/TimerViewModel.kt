package com.timer.app.ui

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timer.app.data.DashboardSnapshot
import com.timer.app.data.RoomTimerRepository
import com.timer.app.data.TaskInstanceEntity
import com.timer.app.data.TaskRuntimeStateEntity
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import com.timer.app.domain.DurationFormatter
import com.timer.app.domain.StatsCalculator
import com.timer.app.domain.StatsSummary
import com.timer.app.domain.TimerMath
import com.timer.app.scheduler.DeadlineAlarmScheduler
import com.timer.app.service.TimerForegroundService
import com.timer.app.service.TimerForegroundServiceStartResult
import com.timer.app.timerApplication
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val ticker = flow {
    while (true) {
        emit(Unit)
        delay(1_000L)
    }
}

data class TaskUiModel(
    val instance: TaskInstanceEntity,
    val state: TaskRuntimeStateEntity?,
    val displayText: String,
    val statusText: String,
    val actionStatus: String,
    val progress: Float?,
    val windowText: String?
)

data class TimerUiState(
    val localDate: LocalDate = LocalDate.now(),
    val tasks: List<TaskUiModel> = emptyList(),
    val stats: StatsSummary = StatsCalculator.empty(),
    val notificationPermissionGranted: Boolean = true,
    val serviceWarningMessage: String? = null,
    val isLoading: Boolean = true
)

class TimerViewModel(
    private val appContext: Context,
    private val repository: RoomTimerRepository,
    private val deadlineAlarmScheduler: DeadlineAlarmScheduler
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val notificationPermissionGranted = MutableStateFlow(true)
    private val serviceWarningMessage = MutableStateFlow<String?>(null)

    val uiState = combine(
        selectedDate,
        repository.observeDashboardSnapshot(),
        ticker,
        notificationPermissionGranted,
        serviceWarningMessage
    ) { date, snapshot, _, notificationsGranted, warningMessage ->
        buildUiState(date, snapshot, notificationsGranted, warningMessage)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TimerUiState(isLoading = true)
    )

    init {
        viewModelScope.launch {
            while (true) {
                reconcileDeadlinesAndNotify()
                delay(DEADLINE_RECONCILE_INTERVAL_MILLIS)
            }
        }
    }

    fun setNotificationPermissionGranted(granted: Boolean) {
        notificationPermissionGranted.value = granted
    }

    fun createTask(
        name: String,
        type: String,
        countdownMinutes: Long?,
        startTime: String?,
        endTime: String?
    ) {
        viewModelScope.launch {
            val color = when (type) {
                TaskTypes.COUNT_DOWN -> 0xFF7C3AED
                TaskTypes.TIME_WINDOW -> 0xFF0F766E
                else -> 0xFF0284C7
            }
            repository.createTaskInstance(
                name = name,
                type = type,
                localDate = selectedDate.value,
                targetDurationMillis = if (type == TaskTypes.COUNT_DOWN) {
                    (countdownMinutes ?: 25L).coerceAtLeast(1L) * 60_000L
                } else {
                    null
                },
                startMinuteOfDay = if (type == TaskTypes.TIME_WINDOW) parseMinuteOfDay(startTime) else null,
                endMinuteOfDay = if (type == TaskTypes.TIME_WINDOW) parseMinuteOfDay(endTime) else null,
                colorArgb = color
            )
            reconcileAndSchedule()
        }
    }

    fun startTask(instanceId: String) {
        viewModelScope.launch {
            repository.startInstance(instanceId)
            handleServiceStartResult(TimerForegroundService.start(appContext))
        }
    }

    fun pauseTask(instanceId: String) {
        viewModelScope.launch { repository.pauseInstance(instanceId) }
    }

    fun resumeTask(instanceId: String) {
        viewModelScope.launch {
            repository.resumeInstance(instanceId)
            handleServiceStartResult(TimerForegroundService.start(appContext))
        }
    }

    fun completeTask(instanceId: String) {
        viewModelScope.launch {
            repository.completeInstanceManually(instanceId)
            deadlineAlarmScheduler.cancelFor(instanceId)
            reconcileAndSchedule()
        }
    }

    fun cancelTask(instanceId: String) {
        viewModelScope.launch {
            repository.cancelInstance(instanceId)
            deadlineAlarmScheduler.cancelFor(instanceId)
            reconcileAndSchedule()
        }
    }

    fun archiveTask(instanceId: String) {
        viewModelScope.launch {
            repository.archiveInstance(instanceId)
            deadlineAlarmScheduler.cancelFor(instanceId)
            reconcileAndSchedule()
        }
    }

    private fun handleServiceStartResult(result: TimerForegroundServiceStartResult) {
        serviceWarningMessage.value = when (result) {
            TimerForegroundServiceStartResult.Started -> null
            is TimerForegroundServiceStartResult.Failed ->
                "Background timer service did not start (${result.reason}). " +
                    "Timing state is saved, but background alerts may be limited."
        }
    }

    private fun buildUiState(
        date: LocalDate,
        snapshot: DashboardSnapshot,
        notificationsGranted: Boolean,
        warningMessage: String?
    ): TimerUiState {
        val nowEpoch = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val statesByInstance = snapshot.states.associateBy { it.instanceId }
        val selectedDateString = date.toString()
        val taskModels = snapshot.instances
            .filter { it.localDate == selectedDateString && !it.archived }
            .map { instance ->
                val state = statesByInstance[instance.id]
                val display = if (instance.type == TaskTypes.TIME_WINDOW) 0L else TimerMath.displayMillis(instance, state, nowElapsed)
                val progress = if (instance.type == TaskTypes.COUNT_DOWN && instance.targetDurationMillis != null && instance.targetDurationMillis > 0L) {
                    val remaining = TimerMath.remainingMillis(instance, state, nowElapsed) ?: instance.targetDurationMillis
                    1f - (remaining.toFloat() / instance.targetDurationMillis.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                val actionStatus = actionStatus(instance, nowEpoch)
                TaskUiModel(
                    instance = instance,
                    state = state,
                    displayText = if (instance.type == TaskTypes.TIME_WINDOW) "" else DurationFormatter.clock(display),
                    statusText = statusText(actionStatus),
                    actionStatus = actionStatus,
                    progress = progress,
                    windowText = windowText(instance, nowEpoch)
                )
            }
        val stats = StatsCalculator.calculate(
            instances = snapshot.instances,
            states = snapshot.states,
            sessions = snapshot.sessions,
            nowEpochMillis = nowEpoch,
            nowElapsedRealtimeMillis = nowElapsed
        )
        return TimerUiState(
            localDate = date,
            tasks = taskModels,
            stats = stats,
            notificationPermissionGranted = notificationsGranted,
            serviceWarningMessage = warningMessage,
            isLoading = false
        )
    }

    private suspend fun reconcileDeadlinesAndNotify() {
        val result = repository.reconcileDeadlines()
        result.completedCountdowns.forEach { appContext.timerApplication().container.notificationController.showCountdownCompleted(it) }
        result.missedTimeWindows.forEach { appContext.timerApplication().container.notificationController.showTimeWindowMissed(it) }
    }

    private suspend fun reconcileAndSchedule() {
        reconcileDeadlinesAndNotify()
        deadlineAlarmScheduler.scheduleFor(repository.getAllInstances())
    }

    private fun statusText(status: String): String = when (status) {
        TaskStatuses.PLANNED -> "Planned"
        TaskStatuses.READY -> "Ready"
        TaskStatuses.RUNNING -> "Running"
        TaskStatuses.PAUSED -> "Paused"
        TaskStatuses.COMPLETED -> "Completed"
        TaskStatuses.MISSED -> "Missed"
        TaskStatuses.CANCELLED -> "Cancelled"
        else -> "Unknown"
    }

    private fun actionStatus(instance: TaskInstanceEntity, nowEpoch: Long): String {
        if (instance.type != TaskTypes.TIME_WINDOW || instance.status != TaskStatuses.PLANNED) return instance.status
        val start = instance.plannedStartEpochMillis ?: return instance.status
        val end = instance.plannedEndEpochMillis ?: return instance.status
        return if (nowEpoch >= start && nowEpoch < end) TaskStatuses.READY else instance.status
    }

    private fun windowText(instance: TaskInstanceEntity, nowEpoch: Long): String? {
        if (instance.type != TaskTypes.TIME_WINDOW) return null
        val start = instance.plannedStartEpochMillis ?: return "No start time"
        val end = instance.plannedEndEpochMillis ?: return "No end time"
        val startText = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)
        val endText = java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)
        return when {
            instance.status == TaskStatuses.COMPLETED -> "$startText-$endText / completed"
            instance.status == TaskStatuses.MISSED -> "$startText-$endText / missed"
            nowEpoch < start -> "$startText-$endText / starts later"
            nowEpoch < end -> "$startText-$endText / complete now"
            else -> "$startText-$endText / overdue"
        }
    }

    private fun parseMinuteOfDay(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: return null
        val minute = parts[1].toIntOrNull()?.coerceIn(0, 59) ?: return null
        return hour * 60 + minute
    }
}

class TimerViewModelFactory(
    private val appContext: Context,
    private val repository: RoomTimerRepository,
    private val deadlineAlarmScheduler: DeadlineAlarmScheduler
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TimerViewModel::class.java)) {
            return TimerViewModel(appContext.applicationContext, repository, deadlineAlarmScheduler) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

private const val DEADLINE_RECONCILE_INTERVAL_MILLIS = 15_000L
