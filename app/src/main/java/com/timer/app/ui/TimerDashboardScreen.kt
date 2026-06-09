@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.timer.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.timer.app.R
import com.timer.app.data.AccentPalettes
import com.timer.app.data.AppPreferencesSnapshot
import com.timer.app.data.CategoryDraft
import com.timer.app.data.DashboardLayouts
import com.timer.app.data.EnergyModes
import com.timer.app.data.GoalDraft
import com.timer.app.data.GoalMetricTypes
import com.timer.app.data.GoalScopes
import com.timer.app.data.RepeatModes
import com.timer.app.data.SessionModes
import com.timer.app.data.TaskCategoryEntity
import com.timer.app.data.TaskDraft
import com.timer.app.data.TaskPriorities
import com.timer.app.data.TaskSortModes
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import com.timer.app.data.ThemeModes
import com.timer.app.domain.DurationFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class LaunchRequest(
    val type: String,
    val sharedText: String? = null
)

private const val LAUNCH_CREATE_TASK = "CREATE_TASK"
private const val LAUNCH_CREATE_ROUTINE = "CREATE_ROUTINE"
private const val LAUNCH_OPEN_FOCUS = "OPEN_FOCUS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerDashboardScreen(
    uiState: TimerUiState,
    launchRequest: LaunchRequest?,
    onLaunchRequestHandled: () -> Unit,
    onCreateTask: (TaskDraft) -> Unit,
    onCreateCategory: (CategoryDraft) -> Unit,
    onCreateGoal: (GoalDraft) -> Unit,
    onCreateTodayFromRoutine: (String) -> Unit,
    onArchiveRoutine: (String) -> Unit,
    onSelectTab: (AppTab) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onChangeMonth: (Long) -> Unit,
    onOpenFocus: (String?) -> Unit,
    onCloseFocus: () -> Unit,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onArchive: (String) -> Unit,
    onSaveResultNote: (String, String) -> Unit,
    onUpdateThemeMode: (String) -> Unit,
    onUpdateAccentPalette: (String) -> Unit,
    onUpdateDynamicColor: (Boolean) -> Unit,
    onUpdateDashboardLayout: (String) -> Unit,
    onUpdateSortMode: (String) -> Unit,
    onUpdateShowCompleted: (Boolean) -> Unit,
    onUpdateEnergyMode: (String) -> Unit,
    onUpdateKeepScreenOn: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    cloudSyncState: CloudSyncSettingsUiState,
    onSaveCloudSyncSettings: (com.timer.app.sync.CloudSyncSettingsDraft) -> Unit,
    onClearCloudSyncToken: () -> Unit,
    onSyncCloudNow: () -> Unit,
    onRestoreCloudLatest: () -> Unit,
    onClearStatusMessage: () -> Unit
) {
    var showTaskEditor by remember { mutableStateOf(false) }
    var showRoutineEditor by remember { mutableStateOf(false) }
    var showCategoryEditor by remember { mutableStateOf(false) }
    var showGoalEditor by remember { mutableStateOf(false) }
    var noteEditorTarget by remember { mutableStateOf<HistoryUiModel?>(null) }
    var taskEditorSeed by remember { mutableStateOf("") }

    LaunchedEffect(launchRequest) {
        when (launchRequest?.type) {
            LAUNCH_CREATE_TASK -> {
                taskEditorSeed = launchRequest.sharedText.orEmpty()
                showTaskEditor = true
            }
            LAUNCH_CREATE_ROUTINE -> {
                taskEditorSeed = launchRequest.sharedText.orEmpty()
                showRoutineEditor = true
            }
            LAUNCH_OPEN_FOCUS -> {
                onOpenFocus(uiState.tasks.firstOrNull { it.isRunning }?.instance?.id)
            }
        }
        if (launchRequest != null) {
            onLaunchRequestHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (uiState.activeTab) {
                                AppTab.TODAY -> stringResource(R.string.tab_today)
                                AppTab.ROUTINES -> stringResource(R.string.tab_routines)
                                AppTab.CALENDAR -> stringResource(R.string.tab_calendar)
                                AppTab.INSIGHTS -> stringResource(R.string.tab_insights)
                                AppTab.SETTINGS -> stringResource(R.string.tab_settings)
                            },
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = uiState.selectedDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = uiState.activeTab == tab,
                        onClick = { onSelectTab(tab) },
                        icon = {
                            Icon(
                                painter = painterResource(tabIconRes(tab)),
                                contentDescription = null
                            )
                        },
                        label = { Text(tabLabel(tab)) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (uiState.activeTab == AppTab.TODAY || uiState.activeTab == AppTab.ROUTINES) {
                FloatingActionButton(
                    onClick = {
                        if (uiState.activeTab == AppTab.ROUTINES) {
                            showRoutineEditor = true
                        } else {
                            showTaskEditor = true
                        }
                    }
                ) {
                    Text("+")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            uiState.statusMessage?.let { message ->
                BannerMessage(message = message, onDismiss = onClearStatusMessage)
            }
            when (uiState.activeTab) {
                AppTab.TODAY -> TodayScreen(
                    uiState = uiState,
                    onOpenTaskEditor = { showTaskEditor = true },
                    onOpenFocus = onOpenFocus,
                    onStart = onStart,
                    onPause = onPause,
                    onResume = onResume,
                    onComplete = onComplete,
                    onCancel = onCancel,
                    onArchive = onArchive
                )
                AppTab.ROUTINES -> RoutinesScreen(
                    uiState = uiState,
                    onCreateTodayFromRoutine = onCreateTodayFromRoutine,
                    onArchiveRoutine = onArchiveRoutine,
                    onOpenRoutineEditor = { showRoutineEditor = true },
                    onOpenCategoryEditor = { showCategoryEditor = true },
                    onOpenGoalEditor = { showGoalEditor = true }
                )
                AppTab.CALENDAR -> CalendarScreen(
                    uiState = uiState,
                    onChangeMonth = onChangeMonth,
                    onSelectDate = onSelectDate,
                    onOpenFocus = onOpenFocus,
                    onStart = onStart,
                    onPause = onPause,
                    onResume = onResume,
                    onComplete = onComplete,
                    onCancel = onCancel,
                    onArchive = onArchive
                )
                AppTab.INSIGHTS -> InsightsScreen(
                    uiState = uiState,
                    onEditResultNote = { noteEditorTarget = it }
                )
                AppTab.SETTINGS -> SettingsScreen(
                    preferences = uiState.appearance,
                    notificationPermissionGranted = uiState.notificationPermissionGranted,
                    serviceWarningMessage = uiState.serviceWarningMessage,
                    onUpdateThemeMode = onUpdateThemeMode,
                    onUpdateAccentPalette = onUpdateAccentPalette,
                    onUpdateDynamicColor = onUpdateDynamicColor,
                    onUpdateDashboardLayout = onUpdateDashboardLayout,
                    onUpdateSortMode = onUpdateSortMode,
                    onUpdateShowCompleted = onUpdateShowCompleted,
                    onUpdateEnergyMode = onUpdateEnergyMode,
                    onUpdateKeepScreenOn = onUpdateKeepScreenOn,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onExportBackup = onExportBackup,
                    onImportBackup = onImportBackup,
                    cloudSyncState = cloudSyncState,
                    onSaveCloudSyncSettings = onSaveCloudSyncSettings,
                    onClearCloudSyncToken = onClearCloudSyncToken,
                    onSyncCloudNow = onSyncCloudNow,
                    onRestoreCloudLatest = onRestoreCloudLatest
                )
            }
        }
    }

    if (showTaskEditor) {
        TaskEditorDialog(
            title = stringResource(R.string.dialog_new_task),
            seedName = taskEditorSeed,
            saveAsRoutineDefault = false,
            categories = uiState.categories,
            selectedDate = uiState.selectedDate,
            onDismiss = {
                showTaskEditor = false
                taskEditorSeed = ""
            },
            onSubmit = {
                onCreateTask(it)
                showTaskEditor = false
                taskEditorSeed = ""
            }
        )
    }

    if (showRoutineEditor) {
        TaskEditorDialog(
            title = stringResource(R.string.dialog_new_routine),
            seedName = taskEditorSeed,
            saveAsRoutineDefault = true,
            categories = uiState.categories,
            selectedDate = uiState.selectedDate,
            onDismiss = {
                showRoutineEditor = false
                taskEditorSeed = ""
            },
            onSubmit = {
                onCreateTask(it.copy(saveAsRoutine = true))
                showRoutineEditor = false
                taskEditorSeed = ""
            }
        )
    }

    if (showCategoryEditor) {
        CategoryEditorDialog(
            onDismiss = { showCategoryEditor = false },
            onSubmit = {
                onCreateCategory(it)
                showCategoryEditor = false
            }
        )
    }

    if (showGoalEditor) {
        GoalEditorDialog(
            categories = uiState.categories,
            onDismiss = { showGoalEditor = false },
            onSubmit = {
                onCreateGoal(it)
                showGoalEditor = false
            }
        )
    }

    noteEditorTarget?.let { target ->
        ResultNoteDialog(
            target = target,
            onDismiss = { noteEditorTarget = null },
            onSave = { note ->
                onSaveResultNote(target.instanceId, note)
                noteEditorTarget = null
            }
        )
    }

    uiState.focusTask?.let { task ->
        FocusModeDialog(
            task = task,
            keepScreenOn = uiState.appearance.keepScreenOnInFocus,
            onClose = onCloseFocus,
            onStart = { onStart(task.instance.id) },
            onPause = { onPause(task.instance.id) },
            onResume = { onResume(task.instance.id) },
            onComplete = { onComplete(task.instance.id) },
            onCancel = { onCancel(task.instance.id) }
        )
    }
}

@Composable
private fun tabLabel(tab: AppTab): String = when (tab) {
    AppTab.TODAY -> stringResource(R.string.tab_today)
    AppTab.ROUTINES -> stringResource(R.string.tab_routines)
    AppTab.CALENDAR -> stringResource(R.string.tab_calendar)
    AppTab.INSIGHTS -> stringResource(R.string.tab_insights)
    AppTab.SETTINGS -> stringResource(R.string.tab_settings)
}

@DrawableRes
private fun tabIconRes(tab: AppTab): Int = when (tab) {
    AppTab.TODAY -> R.drawable.ic_tab_today
    AppTab.ROUTINES -> R.drawable.ic_tab_routines
    AppTab.CALENDAR -> R.drawable.ic_tab_calendar
    AppTab.INSIGHTS -> R.drawable.ic_tab_insights
    AppTab.SETTINGS -> R.drawable.ic_tab_settings
}

@Composable
private fun BannerMessage(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_got_it)) }
        }
    }
}

@Composable
private fun TodayScreen(
    uiState: TimerUiState,
    onOpenTaskEditor: () -> Unit,
    onOpenFocus: (String?) -> Unit,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onArchive: (String) -> Unit
) {
    val runningTask = uiState.tasks.firstOrNull { it.isRunning }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (uiState.appearance.dashboardLayout == DashboardLayouts.FOCUS_FIRST && runningTask != null) {
            item { FocusFirstCard(runningTask, onOpenFocus) }
        }
        if (uiState.appearance.dashboardLayout != DashboardLayouts.TODAY_FIRST) {
            item { HeroStatsCard(uiState) }
        }
        if (!uiState.notificationPermissionGranted) {
            item {
                NotificationWarningCard(
                    title = stringResource(R.string.notifications_off_title),
                    body = stringResource(R.string.notifications_off_message),
                    action = stringResource(R.string.action_open_settings_hint)
                )
            }
        }
        uiState.serviceWarningMessage?.let { warning ->
            item {
                NotificationWarningCard(
                    title = stringResource(R.string.background_service_warning_title),
                    body = warning,
                    action = null
                )
            }
        }
        if (uiState.appearance.dashboardLayout == DashboardLayouts.TODAY_FIRST) {
            item { HeroStatsCard(uiState) }
        }
        if (uiState.suggestions.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(R.string.section_suggestions),
                    subtitle = stringResource(R.string.section_suggestions_subtitle)
                )
            }
            items(uiState.suggestions) { suggestion ->
                SuggestionCard(suggestion)
            }
        }
        item {
            SectionHeader(
                title = stringResource(R.string.section_today_tasks),
                subtitle = stringResource(R.string.today_tasks_subtitle_count, uiState.tasks.size)
            )
        }
        if (uiState.tasks.isEmpty()) {
            item { EmptyState(onCreate = onOpenTaskEditor) }
        } else {
            items(uiState.tasks, key = { it.instance.id }) { task ->
                TaskCard(
                    model = task,
                    onOpenFocus = onOpenFocus,
                    onStart = onStart,
                    onPause = onPause,
                    onResume = onResume,
                    onComplete = onComplete,
                    onCancel = onCancel,
                    onArchive = onArchive
                )
            }
        }
        item { Spacer(Modifier.height(64.dp)) }
    }
}

@Composable
private fun FocusFirstCard(task: TaskUiModel, onOpenFocus: (String?) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.layout_focus_first), fontWeight = FontWeight.Bold)
                Text(task.instance.nameSnapshot, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(task.displayText, style = MaterialTheme.typography.headlineMedium)
            }
            Button(onClick = { onOpenFocus(task.instance.id) }) {
                Text(stringResource(R.string.action_focus))
            }
        }
    }
}

@Composable
private fun HeroStatsCard(uiState: TimerUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = uiState.selectedDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = DurationFormatter.clock(uiState.stats.trackedTodayMillis),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricPill(stringResource(R.string.metric_done), "${uiState.stats.completedTodayCount}/${uiState.stats.plannedTodayCount}")
                MetricPill(stringResource(R.string.metric_score), "${uiState.stats.dailyScore}")
                MetricPill(stringResource(R.string.metric_streak), "${uiState.stats.currentStreakDays}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricPill(stringResource(R.string.metric_week), DurationFormatter.compact(uiState.stats.trackedWeekMillis))
                MetricPill(stringResource(R.string.metric_month), DurationFormatter.compact(uiState.stats.trackedMonthMillis))
                MetricPill(stringResource(R.string.metric_window), "${(uiState.stats.timeWindowCompletionRate * 100).toInt()}%")
            }
            if (uiState.stats.topTasks.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.metric_top,
                        uiState.stats.topTasks.joinToString(" · ") {
                            "${it.taskName} ${DurationFormatter.compact(it.durationMillis)}"
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    AssistChip(onClick = {}, label = { Text("$label $value") })
}

@Composable
private fun NotificationWarningCard(title: String, body: String, action: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            action?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun SuggestionCard(model: SuggestionUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(model.title, fontWeight = FontWeight.Bold)
            Text(model.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TaskCard(
    model: TaskUiModel,
    onOpenFocus: (String?) -> Unit,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onArchive: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(Color(model.instance.colorArgb), CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.instance.nameSnapshot, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(model.statusText, style = MaterialTheme.typography.bodySmall)
                    model.scheduleText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (model.instance.type != TaskTypes.TIME_WINDOW) {
                    Text(model.displayText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                }
            }
            if (model.metaText.isNotBlank()) {
                Text(model.metaText, style = MaterialTheme.typography.bodySmall)
            }
            model.noteText?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            model.pomodoroText?.let {
                AssistChip(onClick = {}, label = { Text(it) })
            }
            model.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.supportsFocusMode) {
                    OutlinedButton(onClick = { onOpenFocus(model.instance.id) }) {
                        Text(stringResource(R.string.action_focus))
                    }
                }
                TaskActionButtons(
                    model = model,
                    onStart = onStart,
                    onPause = onPause,
                    onResume = onResume,
                    onComplete = onComplete,
                    onCancel = onCancel,
                    onArchive = onArchive
                )
            }
        }
    }
}

@Composable
private fun TaskActionButtons(
    model: TaskUiModel,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onArchive: (String) -> Unit
) {
    when (model.instance.type) {
        TaskTypes.TIME_WINDOW -> when (model.actionStatus) {
            TaskStatuses.READY -> {
                Button(onClick = { onComplete(model.instance.id) }) { Text(stringResource(R.string.action_complete)) }
                OutlinedButton(onClick = { onCancel(model.instance.id) }) { Text(stringResource(R.string.action_cancel)) }
            }
            TaskStatuses.PLANNED -> OutlinedButton(onClick = { onCancel(model.instance.id) }) { Text(stringResource(R.string.action_cancel)) }
            else -> TextButton(onClick = { onArchive(model.instance.id) }) { Text(stringResource(R.string.action_archive)) }
        }
        TaskTypes.COUNT_UP -> when (model.actionStatus) {
            TaskStatuses.READY -> {
                Button(onClick = { onStart(model.instance.id) }) { Text(stringResource(R.string.action_start)) }
                OutlinedButton(onClick = { onCancel(model.instance.id) }) { Text(stringResource(R.string.action_cancel)) }
            }
            TaskStatuses.RUNNING -> {
                Button(onClick = { onPause(model.instance.id) }) { Text(stringResource(R.string.action_pause)) }
                OutlinedButton(onClick = { onComplete(model.instance.id) }) { Text(stringResource(R.string.action_complete)) }
            }
            TaskStatuses.PAUSED -> {
                Button(onClick = { onResume(model.instance.id) }) { Text(stringResource(R.string.action_resume)) }
                OutlinedButton(onClick = { onComplete(model.instance.id) }) { Text(stringResource(R.string.action_complete)) }
            }
            else -> TextButton(onClick = { onArchive(model.instance.id) }) { Text(stringResource(R.string.action_archive)) }
        }
        TaskTypes.COUNT_DOWN -> when (model.actionStatus) {
            TaskStatuses.READY -> {
                Button(onClick = { onStart(model.instance.id) }) { Text(stringResource(R.string.action_start)) }
                OutlinedButton(onClick = { onCancel(model.instance.id) }) { Text(stringResource(R.string.action_cancel)) }
            }
            TaskStatuses.RUNNING -> {
                Button(onClick = { onPause(model.instance.id) }) { Text(stringResource(R.string.action_pause)) }
                OutlinedButton(onClick = { onCancel(model.instance.id) }) { Text(stringResource(R.string.action_cancel)) }
            }
            TaskStatuses.PAUSED -> {
                Button(onClick = { onResume(model.instance.id) }) { Text(stringResource(R.string.action_resume)) }
                OutlinedButton(onClick = { onCancel(model.instance.id) }) { Text(stringResource(R.string.action_cancel)) }
            }
            else -> TextButton(onClick = { onArchive(model.instance.id) }) { Text(stringResource(R.string.action_archive)) }
        }
    }
}

@Composable
private fun RoutinesScreen(
    uiState: TimerUiState,
    onCreateTodayFromRoutine: (String) -> Unit,
    onArchiveRoutine: (String) -> Unit,
    onOpenRoutineEditor: () -> Unit,
    onOpenCategoryEditor: () -> Unit,
    onOpenGoalEditor: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ActionRailCard(
                title = stringResource(R.string.section_routine_center),
                body = stringResource(R.string.section_routine_center_subtitle),
                primary = stringResource(R.string.action_create_routine),
                secondary = stringResource(R.string.action_add_category),
                onPrimary = onOpenRoutineEditor,
                onSecondary = onOpenCategoryEditor
            )
        }
        item {
            SectionHeader(
                title = stringResource(R.string.section_categories),
                subtitle = stringResource(R.string.section_categories_subtitle, uiState.categories.size)
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.categories.forEach { category ->
                    AssistChip(onClick = {}, label = { Text(category.name) })
                }
            }
        }
        item {
            SectionHeader(
                title = stringResource(R.string.section_goals),
                subtitle = stringResource(R.string.section_goals_subtitle, uiState.goals.size)
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.goals.forEach { goal ->
                    ProgressInfoCard(goal.goal.name, goal.progressText, goal.progress)
                }
                OutlinedButton(onClick = onOpenGoalEditor) {
                    Text(stringResource(R.string.action_add_goal))
                }
            }
        }
        item {
            SectionHeader(
                title = stringResource(R.string.section_routines),
                subtitle = stringResource(R.string.section_routines_subtitle, uiState.routines.size)
            )
        }
        if (uiState.routines.isEmpty()) {
            item { EmptyRoutineState(onOpenRoutineEditor) }
        } else {
            items(uiState.routines, key = { it.template.id }) { routine ->
                RoutineCard(routine, onCreateTodayFromRoutine, onArchiveRoutine)
            }
        }
    }
}

@Composable
private fun ActionRailCard(
    title: String,
    body: String,
    primary: String,
    secondary: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrimary) { Text(primary) }
                OutlinedButton(onClick = onSecondary) { Text(secondary) }
            }
        }
    }
}

@Composable
private fun RoutineCard(
    model: RoutineUiModel,
    onCreateTodayFromRoutine: (String) -> Unit,
    onArchiveRoutine: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.template.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(model.categoryName, model.scheduleText).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text(model.reminderText, style = MaterialTheme.typography.bodySmall)
            Text(model.statsText, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCreateTodayFromRoutine(model.template.id) }) {
                    Text(
                        if (model.todayInstanceId == null) {
                            stringResource(R.string.action_create_today)
                        } else {
                            stringResource(R.string.action_created_today)
                        }
                    )
                }
                OutlinedButton(onClick = { onArchiveRoutine(model.template.id) }) {
                    Text(stringResource(R.string.action_archive))
                }
            }
        }
    }
}

@Composable
private fun CalendarScreen(
    uiState: TimerUiState,
    onChangeMonth: (Long) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onOpenFocus: (String?) -> Unit,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onArchive: (String) -> Unit
) {
    val selectedTasks = uiState.tasks
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { onChangeMonth(-1) }) { Text("‹") }
                        Text(uiState.selectedMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { onChangeMonth(1) }) { Text("›") }
                    }
                    CalendarGrid(uiState.calendarDays, onSelectDate)
                }
            }
        }
        item {
            SectionHeader(
                title = stringResource(R.string.section_selected_day),
                subtitle = uiState.selectedDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()))
            )
        }
        if (selectedTasks.isEmpty()) {
            item { EmptyCalendarSelection() }
        } else {
            items(selectedTasks, key = { it.instance.id }) { task ->
                TaskCard(task, onOpenFocus, onStart, onPause, onResume, onComplete, onCancel, onArchive)
            }
        }
    }
}

@Composable
private fun CalendarGrid(days: List<CalendarDayUiModel>, onSelectDate: (LocalDate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.values().forEach { day ->
                Text(
                    day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    val containerColor = when {
                        day.isSelected -> MaterialTheme.colorScheme.primaryContainer
                        day.missedCount > 0 -> MaterialTheme.colorScheme.errorContainer
                        day.completedCount > 0 -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectDate(day.date) },
                        colors = CardDefaults.cardColors(containerColor = containerColor),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("${day.date.dayOfMonth}", fontWeight = if (day.isToday) FontWeight.ExtraBold else FontWeight.Medium)
                            Text("${day.completedCount}/${day.plannedCount}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightsScreen(
    uiState: TimerUiState,
    onEditResultNote: (HistoryUiModel) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionHeader(
                title = stringResource(R.string.section_overview),
                subtitle = stringResource(R.string.section_overview_subtitle)
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBox(
                    label = stringResource(R.string.metric_completion),
                    value = "${(uiState.stats.completionRateToday * 100).toInt()}%",
                    modifier = Modifier.weight(1f)
                )
                StatBox(
                    label = stringResource(R.string.metric_avg_session),
                    value = DurationFormatter.compact(uiState.stats.averageSessionMillis),
                    modifier = Modifier.weight(1f)
                )
                StatBox(
                    label = stringResource(R.string.metric_focus_sessions),
                    value = "${uiState.stats.focusSessionsTodayCount}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            BreakdownSection(
                title = stringResource(R.string.section_category_breakdown),
                items = uiState.stats.categoryBreakdown
            )
        }
        item {
            BreakdownSection(
                title = stringResource(R.string.section_project_breakdown),
                items = uiState.stats.projectBreakdown
            )
        }
        item {
            SectionHeader(
                title = stringResource(R.string.section_history),
                subtitle = stringResource(R.string.section_history_subtitle)
            )
        }
        if (uiState.history.isEmpty()) {
            item { EmptyInsightCard() }
        } else {
            items(uiState.history, key = { it.instanceId }) { history ->
                HistoryCard(history, onEditResultNote)
            }
        }
        if (uiState.audits.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(R.string.section_audit),
                    subtitle = stringResource(R.string.section_audit_subtitle)
                )
            }
            items(uiState.audits, key = { it.id }) { audit ->
                AuditCard(audit)
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BreakdownSection(title: String, items: List<com.timer.app.domain.BreakdownStat>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (items.isEmpty()) {
                Text(stringResource(R.string.empty_breakdown), style = MaterialTheme.typography.bodySmall)
            } else {
                items.forEach { item ->
                    ProgressInfoCard(
                        title = item.label,
                        body = stringResource(
                            R.string.breakdown_summary,
                            item.completedCount,
                            item.plannedCount,
                            DurationFormatter.compact(item.trackedMillis)
                        ),
                        progress = item.completionRate
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressInfoCard(title: String, body: String, progress: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HistoryCard(model: HistoryUiModel, onEditResultNote: (HistoryUiModel) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(listOf(model.localDate, model.statusText, model.trackedText).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            listOfNotNull(model.categoryName, model.projectName).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            model.resultNote?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { onEditResultNote(model) }) {
                Text(stringResource(R.string.action_edit_note))
            }
        }
    }
}

@Composable
private fun EmptyInsightCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.empty_history), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.empty_history_body), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AuditCard(model: AuditUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(model.title, fontWeight = FontWeight.Bold)
            Text(model.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsScreen(
    preferences: AppPreferencesSnapshot,
    notificationPermissionGranted: Boolean,
    serviceWarningMessage: String?,
    onUpdateThemeMode: (String) -> Unit,
    onUpdateAccentPalette: (String) -> Unit,
    onUpdateDynamicColor: (Boolean) -> Unit,
    onUpdateDashboardLayout: (String) -> Unit,
    onUpdateSortMode: (String) -> Unit,
    onUpdateShowCompleted: (Boolean) -> Unit,
    onUpdateEnergyMode: (String) -> Unit,
    onUpdateKeepScreenOn: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    cloudSyncState: CloudSyncSettingsUiState,
    onSaveCloudSyncSettings: (com.timer.app.sync.CloudSyncSettingsDraft) -> Unit,
    onClearCloudSyncToken: () -> Unit,
    onSyncCloudNow: () -> Unit,
    onRestoreCloudLatest: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SettingsCard(title = stringResource(R.string.section_appearance)) {
                ChoiceRow(
                    label = stringResource(R.string.setting_theme_mode),
                    options = listOf(
                        ThemeModes.SYSTEM to stringResource(R.string.theme_system),
                        ThemeModes.LIGHT to stringResource(R.string.theme_light),
                        ThemeModes.DARK to stringResource(R.string.theme_dark)
                    ),
                    selected = preferences.themeMode,
                    onSelect = onUpdateThemeMode
                )
                ChoiceRow(
                    label = stringResource(R.string.setting_accent_palette),
                    options = listOf(
                        AccentPalettes.BLUE to stringResource(R.string.accent_blue),
                        AccentPalettes.VIOLET to stringResource(R.string.accent_violet),
                        AccentPalettes.EMERALD to stringResource(R.string.accent_emerald),
                        AccentPalettes.SUNSET to stringResource(R.string.accent_sunset)
                    ),
                    selected = preferences.accentPalette,
                    onSelect = onUpdateAccentPalette
                )
                ToggleRow(
                    label = stringResource(R.string.setting_dynamic_color),
                    checked = preferences.dynamicColor,
                    onCheckedChange = onUpdateDynamicColor
                )
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.section_dashboard_behavior)) {
                ChoiceRow(
                    label = stringResource(R.string.setting_dashboard_layout),
                    options = listOf(
                        DashboardLayouts.TODAY_FIRST to stringResource(R.string.layout_today_first),
                        DashboardLayouts.FOCUS_FIRST to stringResource(R.string.layout_focus_first),
                        DashboardLayouts.STATS_FIRST to stringResource(R.string.layout_stats_first)
                    ),
                    selected = preferences.dashboardLayout,
                    onSelect = onUpdateDashboardLayout
                )
                ChoiceRow(
                    label = stringResource(R.string.setting_sort_mode),
                    options = listOf(
                        TaskSortModes.SMART to stringResource(R.string.sort_smart),
                        TaskSortModes.PRIORITY to stringResource(R.string.sort_priority),
                        TaskSortModes.START_TIME to stringResource(R.string.sort_start_time),
                        TaskSortModes.CREATED_AT to stringResource(R.string.sort_created_at)
                    ),
                    selected = preferences.sortMode,
                    onSelect = onUpdateSortMode
                )
                ToggleRow(
                    label = stringResource(R.string.setting_show_completed),
                    checked = preferences.showCompletedTasks,
                    onCheckedChange = onUpdateShowCompleted
                )
                ToggleRow(
                    label = stringResource(R.string.setting_keep_screen_on),
                    checked = preferences.keepScreenOnInFocus,
                    onCheckedChange = onUpdateKeepScreenOn
                )
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.section_reliability)) {
                ChoiceRow(
                    label = stringResource(R.string.setting_energy_mode),
                    options = listOf(
                        EnergyModes.BALANCED to stringResource(R.string.energy_balanced),
                        EnergyModes.RELIABLE to stringResource(R.string.energy_reliable),
                        EnergyModes.LOW_POWER to stringResource(R.string.energy_low_power)
                    ),
                    selected = preferences.energyMode,
                    onSelect = onUpdateEnergyMode
                )
                ToggleRow(
                    label = stringResource(R.string.setting_notifications),
                    checked = notificationPermissionGranted,
                    onCheckedChange = { if (!notificationPermissionGranted) onRequestNotificationPermission() }
                )
                serviceWarningMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.section_sync_backup)) {
                CloudSyncSettingsSection(
                    state = cloudSyncState,
                    onSave = onSaveCloudSyncSettings,
                    onClearToken = onClearCloudSyncToken,
                    onSyncNow = onSyncCloudNow,
                    onRestoreLatest = onRestoreCloudLatest
                )
                Text(stringResource(R.string.backup_description), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExportBackup) { Text(stringResource(R.string.action_export_backup)) }
                    OutlinedButton(onClick = onImportBackup) { Text(stringResource(R.string.action_import_backup)) }
                }
            }
        }
        item {
            SettingsCard(title = stringResource(R.string.section_widgets_shortcuts)) {
                Text(stringResource(R.string.widgets_shortcuts_description), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, title) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(title) })
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TaskEditorDialog(
    title: String,
    seedName: String,
    saveAsRoutineDefault: Boolean,
    categories: List<TaskCategoryEntity>,
    selectedDate: LocalDate,
    onDismiss: () -> Unit,
    onSubmit: (TaskDraft) -> Unit
) {
    var name by remember(seedName) { mutableStateOf(seedName) }
    var type by remember { mutableStateOf(TaskTypes.COUNT_UP) }
    var countdownMinutes by remember { mutableStateOf("25") }
    var preferredStartTime by remember { mutableStateOf("09:00") }
    var startTime by remember { mutableStateOf("09:00") }
    var endTime by remember { mutableStateOf("10:00") }
    var categoryId by remember { mutableStateOf<String?>(categories.firstOrNull()?.id) }
    var projectName by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(TaskPriorities.MEDIUM) }
    var saveAsRoutine by remember { mutableStateOf(saveAsRoutineDefault) }
    var repeatMode by remember { mutableStateOf(if (saveAsRoutineDefault) RepeatModes.DAILY else RepeatModes.NONE) }
    var repeatInterval by remember { mutableStateOf("1") }
    var customDays by remember { mutableStateOf(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)) }
    var remindersEnabled by remember { mutableStateOf(true) }
    var remindAtStart by remember { mutableStateOf(true) }
    var remindBeforeEndMinutes by remember { mutableStateOf("10") }
    var remindAtDeadline by remember { mutableStateOf(type == TaskTypes.TIME_WINDOW) }
    var sessionMode by remember { mutableStateOf(SessionModes.STANDARD) }
    var pomodoroWork by remember { mutableStateOf("25") }
    var pomodoroBreak by remember { mutableStateOf("5") }
    var pomodoroCycles by remember { mutableStateOf("4") }

    val timeWindowValid = type != TaskTypes.TIME_WINDOW || (isValidClockTime(startTime) && isValidClockTime(endTime))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.field_task_name)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TaskTypes.COUNT_UP to stringResource(R.string.task_type_count_up),
                        TaskTypes.COUNT_DOWN to stringResource(R.string.task_type_count_down),
                        TaskTypes.TIME_WINDOW to stringResource(R.string.task_type_time_window)
                    ).forEach { (value, label) ->
                        FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                    }
                }
                if (type == TaskTypes.COUNT_DOWN) {
                    ChoiceRow(
                        label = stringResource(R.string.field_session_mode),
                        options = listOf(
                            SessionModes.STANDARD to stringResource(R.string.session_mode_standard),
                            SessionModes.POMODORO to stringResource(R.string.session_mode_pomodoro)
                        ),
                        selected = sessionMode,
                        onSelect = { sessionMode = it }
                    )
                }
                if (type == TaskTypes.COUNT_DOWN && sessionMode == SessionModes.STANDARD) {
                    OutlinedTextField(
                        value = countdownMinutes,
                        onValueChange = { countdownMinutes = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.field_countdown_minutes)) },
                        singleLine = true
                    )
                }
                if (type == TaskTypes.COUNT_DOWN && sessionMode == SessionModes.POMODORO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = pomodoroWork, onValueChange = { pomodoroWork = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_pomodoro_work)) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = pomodoroBreak, onValueChange = { pomodoroBreak = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_pomodoro_break)) }, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = pomodoroCycles, onValueChange = { pomodoroCycles = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_pomodoro_cycles)) })
                }
                if (type != TaskTypes.TIME_WINDOW) {
                    OutlinedTextField(value = preferredStartTime, onValueChange = { preferredStartTime = it.take(5) }, label = { Text(stringResource(R.string.field_preferred_start)) }, singleLine = true)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = startTime, onValueChange = { startTime = it.take(5) }, label = { Text(stringResource(R.string.field_start_time)) }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = endTime, onValueChange = { endTime = it.take(5) }, label = { Text(stringResource(R.string.field_end_time)) }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                }
                ChoiceRow(
                    label = stringResource(R.string.field_priority),
                    options = listOf(
                        TaskPriorities.LOW to stringResource(R.string.priority_low),
                        TaskPriorities.MEDIUM to stringResource(R.string.priority_medium),
                        TaskPriorities.HIGH to stringResource(R.string.priority_high)
                    ),
                    selected = priority,
                    onSelect = { priority = it }
                )
                if (categories.isNotEmpty()) {
                    ChoiceRow(
                        label = stringResource(R.string.field_category),
                        options = categories.map { it.id to it.name },
                        selected = categoryId ?: "",
                        onSelect = { categoryId = it }
                    )
                }
                OutlinedTextField(value = projectName, onValueChange = { projectName = it }, label = { Text(stringResource(R.string.field_project)) }, singleLine = true)
                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text(stringResource(R.string.field_tags)) }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.field_note)) })
                ToggleRow(label = stringResource(R.string.field_save_as_routine), checked = saveAsRoutine, onCheckedChange = { saveAsRoutine = it })
                if (saveAsRoutine) {
                    ChoiceRow(
                        label = stringResource(R.string.field_repeat_mode),
                        options = listOf(
                            RepeatModes.DAILY to stringResource(R.string.repeat_daily),
                            RepeatModes.WEEKDAYS to stringResource(R.string.repeat_weekdays),
                            RepeatModes.WEEKLY to stringResource(R.string.repeat_weekly),
                            RepeatModes.CUSTOM_DAYS to stringResource(R.string.repeat_custom),
                            RepeatModes.MONTHLY to stringResource(R.string.repeat_monthly)
                        ),
                        selected = repeatMode,
                        onSelect = { repeatMode = it }
                    )
                    if (repeatMode == RepeatModes.CUSTOM_DAYS) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DayOfWeek.values().forEach { day ->
                                FilterChip(
                                    selected = day in customDays,
                                    onClick = {
                                        customDays = if (day in customDays) customDays - day else customDays + day
                                    },
                                    label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(value = repeatInterval, onValueChange = { repeatInterval = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_repeat_interval)) }, singleLine = true)
                }
                ToggleRow(label = stringResource(R.string.field_enable_reminders), checked = remindersEnabled, onCheckedChange = { remindersEnabled = it })
                if (remindersEnabled) {
                    ToggleRow(label = stringResource(R.string.reminder_start), checked = remindAtStart, onCheckedChange = { remindAtStart = it })
                    OutlinedTextField(value = remindBeforeEndMinutes, onValueChange = { remindBeforeEndMinutes = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_remind_before_end)) }, singleLine = true)
                    ToggleRow(label = stringResource(R.string.reminder_deadline), checked = remindAtDeadline, onCheckedChange = { remindAtDeadline = it })
                }
                if (!timeWindowValid) {
                    Text(stringResource(R.string.field_time_validation), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = timeWindowValid,
                onClick = {
                    val draft = TaskDraft(
                        name = name,
                        type = type,
                        localDate = selectedDate.toString(),
                        countdownMinutes = countdownMinutes.toLongOrNull(),
                        preferredStartTime = preferredStartTime,
                        startTime = if (type == TaskTypes.TIME_WINDOW) startTime else null,
                        endTime = if (type == TaskTypes.TIME_WINDOW) endTime else null,
                        categoryId = categoryId,
                        projectName = projectName,
                        tags = tags,
                        note = note,
                        priority = priority,
                        saveAsRoutine = saveAsRoutine,
                        repeatMode = if (saveAsRoutine) repeatMode else RepeatModes.NONE,
                        repeatDaysCsv = if (repeatMode == RepeatModes.CUSTOM_DAYS) {
                            customDays.sortedBy { it.value }.joinToString(",") { it.value.toString() }
                        } else {
                            null
                        },
                        repeatInterval = repeatInterval.toIntOrNull() ?: 1,
                        remindersEnabled = remindersEnabled,
                        remindAtStart = remindAtStart,
                        remindBeforeEndMinutes = remindBeforeEndMinutes.toIntOrNull(),
                        remindAtDeadline = remindAtDeadline,
                        sessionMode = sessionMode,
                        pomodoroWorkMinutes = pomodoroWork.toIntOrNull(),
                        pomodoroBreakMinutes = pomodoroBreak.toIntOrNull(),
                        pomodoroCycles = pomodoroCycles.toIntOrNull(),
                        colorArgb = when (type) {
                            TaskTypes.COUNT_DOWN -> 0xFF7C3AED
                            TaskTypes.TIME_WINDOW -> 0xFF0F766E
                            else -> 0xFF2563EB
                        }
                    )
                    onSubmit(draft)
                }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun CategoryEditorDialog(onDismiss: () -> Unit, onSubmit: (CategoryDraft) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("4280391419") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_category)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.field_category_name)) })
                OutlinedTextField(value = color, onValueChange = { color = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_color_argb)) })
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(CategoryDraft(name = name, colorArgb = color.toLongOrNull() ?: 0xFF2563EB)) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun GoalEditorDialog(
    categories: List<TaskCategoryEntity>,
    onDismiss: () -> Unit,
    onSubmit: (GoalDraft) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(GoalScopes.DAILY) }
    var metricType by remember { mutableStateOf(GoalMetricTypes.COMPLETED_TASKS) }
    var target by remember { mutableStateOf("3") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var projectName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_goal)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.field_goal_name)) })
                ChoiceRow(
                    label = stringResource(R.string.field_goal_scope),
                    options = listOf(
                        GoalScopes.DAILY to stringResource(R.string.goal_scope_daily),
                        GoalScopes.WEEKLY to stringResource(R.string.goal_scope_weekly),
                        GoalScopes.MONTHLY to stringResource(R.string.goal_scope_monthly)
                    ),
                    selected = scope,
                    onSelect = { scope = it }
                )
                ChoiceRow(
                    label = stringResource(R.string.field_goal_metric),
                    options = listOf(
                        GoalMetricTypes.COMPLETED_TASKS to stringResource(R.string.goal_metric_completed_tasks),
                        GoalMetricTypes.TRACKED_MINUTES to stringResource(R.string.goal_metric_tracked_minutes),
                        GoalMetricTypes.TIME_WINDOW_COMPLETION_RATE to stringResource(R.string.goal_metric_window_rate)
                    ),
                    selected = metricType,
                    onSelect = { metricType = it }
                )
                OutlinedTextField(value = target, onValueChange = { target = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.field_goal_target)) })
                if (categories.isNotEmpty()) {
                    ChoiceRow(
                        label = stringResource(R.string.field_category_optional),
                        options = listOf("" to stringResource(R.string.option_all_categories)) + categories.map { it.id to it.name },
                        selected = categoryId ?: "",
                        onSelect = { categoryId = it.ifBlank { null } }
                    )
                }
                OutlinedTextField(value = projectName, onValueChange = { projectName = it }, label = { Text(stringResource(R.string.field_project_optional)) })
            }
        },
        confirmButton = {
            Button(onClick = {
                onSubmit(
                    GoalDraft(
                        name = name,
                        scope = scope,
                        metricType = metricType,
                        targetValue = target.toLongOrNull() ?: 1L,
                        categoryId = categoryId,
                        projectName = projectName.ifBlank { null }
                    )
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun ResultNoteDialog(target: HistoryUiModel, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(target.instanceId) { mutableStateOf(target.resultNote.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.field_result_note)) }) },
        confirmButton = { Button(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun FocusModeDialog(
    task: TaskUiModel,
    keepScreenOn: Boolean,
    onClose: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(task.instance.nameSnapshot) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.focus_mode_title), style = MaterialTheme.typography.titleMedium)
                Text(task.displayText.ifBlank { task.scheduleText.orEmpty() }, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.ExtraBold)
                task.pomodoroText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(if (keepScreenOn) stringResource(R.string.focus_keep_screen_on) else stringResource(R.string.focus_screen_follow_system), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.instance.type) {
                    TaskTypes.TIME_WINDOW -> {
                        if (task.actionStatus == TaskStatuses.READY) {
                            Button(onClick = onComplete) { Text(stringResource(R.string.action_complete)) }
                        }
                    }
                    TaskTypes.COUNT_UP -> {
                        when (task.actionStatus) {
                            TaskStatuses.READY -> Button(onClick = onStart) { Text(stringResource(R.string.action_start)) }
                            TaskStatuses.RUNNING -> {
                                Button(onClick = onPause) { Text(stringResource(R.string.action_pause)) }
                                OutlinedButton(onClick = onComplete) { Text(stringResource(R.string.action_complete)) }
                            }
                            TaskStatuses.PAUSED -> {
                                Button(onClick = onResume) { Text(stringResource(R.string.action_resume)) }
                                OutlinedButton(onClick = onComplete) { Text(stringResource(R.string.action_complete)) }
                            }
                        }
                    }
                    TaskTypes.COUNT_DOWN -> {
                        when (task.actionStatus) {
                            TaskStatuses.READY -> Button(onClick = onStart) { Text(stringResource(R.string.action_start)) }
                            TaskStatuses.RUNNING -> Button(onClick = onPause) { Text(stringResource(R.string.action_pause)) }
                            TaskStatuses.PAUSED -> Button(onClick = onResume) { Text(stringResource(R.string.action_resume)) }
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (
                    (task.instance.type == TaskTypes.TIME_WINDOW && task.actionStatus == TaskStatuses.READY) ||
                    (task.instance.type == TaskTypes.COUNT_UP && task.actionStatus == TaskStatuses.READY) ||
                    (task.instance.type == TaskTypes.COUNT_DOWN && task.actionStatus in setOf(TaskStatuses.READY, TaskStatuses.RUNNING, TaskStatuses.PAUSED))
                ) {
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }
        }
    )
}

@Composable
private fun EmptyState(onCreate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.empty_no_tasks_today), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.empty_no_tasks_message))
            Button(onClick = onCreate) { Text(stringResource(R.string.action_create_first_task)) }
        }
    }
}

@Composable
private fun EmptyRoutineState(onCreate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.empty_no_routines), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.empty_no_routines_body))
            Button(onClick = onCreate) { Text(stringResource(R.string.action_create_routine)) }
        }
    }
}

@Composable
private fun EmptyCalendarSelection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.empty_calendar_day), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.empty_calendar_day_body))
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

private fun isValidClockTime(value: String): Boolean {
    val parts = value.trim().split(":")
    if (parts.size != 2) return false
    val hour = parts[0].toIntOrNull() ?: return false
    val minute = parts[1].toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}
