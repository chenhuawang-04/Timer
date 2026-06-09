package com.timer.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.timer.app.R
import com.timer.app.data.TaskStatuses
import com.timer.app.data.TaskTypes
import com.timer.app.domain.DurationFormatter
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerDashboardScreen(
    uiState: TimerUiState,
    onCreateTask: (name: String, type: String, countdownMinutes: Long?, startTime: String?, endTime: String?) -> Unit,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onArchive: (String) -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.dashboard_title), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.dashboard_subtitle), style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { HeroStats(uiState) }
            if (!uiState.notificationPermissionGranted) {
                item { NotificationPermissionCard(onRequestNotificationPermission) }
            }
            uiState.serviceWarningMessage?.let { warning ->
                item { ServiceWarningCard(warning) }
            }
            item { WeeklyChart(uiState) }
            item {
                SectionHeader(
                    title = stringResource(R.string.section_today_tasks),
                    subtitle = if (uiState.tasks.isEmpty()) {
                        stringResource(R.string.today_tasks_subtitle_empty)
                    } else {
                        stringResource(R.string.today_tasks_subtitle_count, uiState.tasks.size)
                    }
                )
            }
            if (uiState.tasks.isEmpty()) {
                item { EmptyState(onCreate = { showCreateDialog = true }) }
            } else {
                items(uiState.tasks, key = { it.instance.id }) { task ->
                    TaskCard(
                        model = task,
                        onStart = onStart,
                        onPause = onPause,
                        onResume = onResume,
                        onComplete = onComplete,
                        onCancel = onCancel,
                        onArchive = onArchive
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showCreateDialog) {
        CreateTaskDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, type, countdownMinutes, startTime, endTime ->
                onCreateTask(name, type, countdownMinutes, startTime, endTime)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun HeroStats(uiState: TimerUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(uiState.localDate.toString(), style = MaterialTheme.typography.titleMedium)
            Text(
                DurationFormatter.clock(uiState.stats.trackedTodayMillis),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill(stringResource(R.string.metric_tasks), "${uiState.stats.plannedTodayCount}")
                MetricPill(stringResource(R.string.metric_done), "${uiState.stats.completedTodayCount}")
                MetricPill(stringResource(R.string.metric_missed), "${uiState.stats.missedTodayCount}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricPill(stringResource(R.string.metric_week), compactDurationString(uiState.stats.trackedWeekMillis))
                MetricPill(stringResource(R.string.metric_month), compactDurationString(uiState.stats.trackedMonthMillis))
                MetricPill(stringResource(R.string.metric_window), "${(uiState.stats.timeWindowCompletionRate * 100).toInt()}%")
            }
            if (uiState.stats.topTasks.isNotEmpty()) {
                val topTasksSummary = uiState.stats.topTasks.joinToString {
                    "${it.taskName.ifBlank { stringResource(R.string.task_name_deleted) }} ${compactDurationString(it.durationMillis)}"
                }
                Text(
                    stringResource(R.string.metric_top, topTasksSummary),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
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
private fun NotificationPermissionCard(onRequest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.notifications_off_title), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.notifications_off_message), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onRequest) { Text(stringResource(R.string.action_enable)) }
        }
    }
}

@Composable
private fun ServiceWarningCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(stringResource(R.string.background_service_warning_title), fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WeeklyChart(uiState: TimerUiState) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(
                stringResource(R.string.chart_last_seven_days_title),
                stringResource(R.string.chart_last_seven_days_subtitle)
            )
            val maxMillis = max(1L, uiState.stats.lastSevenDays.maxOfOrNull { it.trackedMillis } ?: 1L)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                val barWidth = size.width / 13f
                val gap = barWidth
                uiState.stats.lastSevenDays.forEachIndexed { index, stat ->
                    val normalized = stat.trackedMillis.toFloat() / maxMillis.toFloat()
                    val height = size.height * normalized.coerceIn(0.04f, 1f)
                    val x = index * (barWidth + gap) + gap / 2f
                    drawRoundRect(
                        color = Color(0xFF60A5FA),
                        topLeft = androidx.compose.ui.geometry.Offset(x, size.height - height),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(12f, 12f)
                    )
                }
                val path = Path()
                uiState.stats.lastSevenDays.forEachIndexed { index, stat ->
                    val normalized = stat.trackedMillis.toFloat() / maxMillis.toFloat()
                    val x = index * (barWidth + gap) + gap / 2f + barWidth / 2f
                    val y = size.height - (size.height * normalized.coerceIn(0.04f, 1f))
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = Color(0xFF7C3AED), style = Stroke(width = 4f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                uiState.stats.lastSevenDays.forEach { stat ->
                    Text(
                        stat.date.format(DateTimeFormatter.ofPattern("E", Locale.getDefault())),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    model: TaskUiModel,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onArchive: (String) -> Unit
) {
    val instance = model.instance
    val typeText = typeLabel(instance.type)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(Color(instance.colorArgb), CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(instance.nameSnapshot, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("$typeText / ${model.statusText}", style = MaterialTheme.typography.bodySmall)
                    model.windowText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (instance.type != TaskTypes.TIME_WINDOW) {
                    Text(model.displayText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                }
            }
            model.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            TaskActions(
                instanceId = instance.id,
                type = instance.type,
                status = model.actionStatus,
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

@Composable
private fun TaskActions(
    instanceId: String,
    type: String,
    status: String,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onArchive: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (type) {
            TaskTypes.TIME_WINDOW -> when (status) {
                TaskStatuses.READY -> {
                    Button(onClick = { onComplete(instanceId) }) { Text(stringResource(R.string.action_complete)) }
                    OutlinedButton(onClick = { onCancel(instanceId) }) { Text(stringResource(R.string.action_cancel)) }
                }
                TaskStatuses.PLANNED -> OutlinedButton(onClick = { onCancel(instanceId) }) { Text(stringResource(R.string.action_cancel)) }
                TaskStatuses.COMPLETED, TaskStatuses.MISSED, TaskStatuses.CANCELLED -> TextButton(onClick = { onArchive(instanceId) }) { Text(stringResource(R.string.action_archive)) }
            }
            TaskTypes.COUNT_UP -> when (status) {
                TaskStatuses.READY -> {
                    Button(onClick = { onStart(instanceId) }) { Text(stringResource(R.string.action_start)) }
                    OutlinedButton(onClick = { onCancel(instanceId) }) { Text(stringResource(R.string.action_cancel)) }
                }
                TaskStatuses.RUNNING -> {
                    Button(onClick = { onPause(instanceId) }) { Text(stringResource(R.string.action_pause)) }
                    OutlinedButton(onClick = { onComplete(instanceId) }) { Text(stringResource(R.string.action_complete)) }
                }
                TaskStatuses.PAUSED -> {
                    Button(onClick = { onResume(instanceId) }) { Text(stringResource(R.string.action_resume)) }
                    OutlinedButton(onClick = { onComplete(instanceId) }) { Text(stringResource(R.string.action_complete)) }
                }
                TaskStatuses.COMPLETED, TaskStatuses.CANCELLED -> TextButton(onClick = { onArchive(instanceId) }) { Text(stringResource(R.string.action_archive)) }
            }
            TaskTypes.COUNT_DOWN -> when (status) {
                TaskStatuses.READY -> {
                    Button(onClick = { onStart(instanceId) }) { Text(stringResource(R.string.action_start)) }
                    OutlinedButton(onClick = { onCancel(instanceId) }) { Text(stringResource(R.string.action_cancel)) }
                }
                TaskStatuses.RUNNING -> {
                    Button(onClick = { onPause(instanceId) }) { Text(stringResource(R.string.action_pause)) }
                    OutlinedButton(onClick = { onCancel(instanceId) }) { Text(stringResource(R.string.action_cancel)) }
                }
                TaskStatuses.PAUSED -> {
                    Button(onClick = { onResume(instanceId) }) { Text(stringResource(R.string.action_resume)) }
                    OutlinedButton(onClick = { onCancel(instanceId) }) { Text(stringResource(R.string.action_cancel)) }
                }
                TaskStatuses.COMPLETED, TaskStatuses.CANCELLED -> TextButton(onClick = { onArchive(instanceId) }) { Text(stringResource(R.string.action_archive)) }
            }
        }
    }
}

@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Long?, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TaskTypes.COUNT_UP) }
    var countdownMinutes by remember { mutableStateOf("25") }
    var startTime by remember { mutableStateOf("09:00") }
    var endTime by remember { mutableStateOf("10:00") }
    val timeWindowInputValid = type != TaskTypes.TIME_WINDOW ||
        (isValidClockTime(startTime) && isValidClockTime(endTime))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_task_name)) },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == TaskTypes.COUNT_UP, onClick = { type = TaskTypes.COUNT_UP }, label = { Text(stringResource(R.string.task_type_count_up)) })
                    FilterChip(selected = type == TaskTypes.COUNT_DOWN, onClick = { type = TaskTypes.COUNT_DOWN }, label = { Text(stringResource(R.string.task_type_count_down)) })
                    FilterChip(selected = type == TaskTypes.TIME_WINDOW, onClick = { type = TaskTypes.TIME_WINDOW }, label = { Text(stringResource(R.string.task_type_time_window_short)) })
                }
                if (type == TaskTypes.COUNT_DOWN) {
                    OutlinedTextField(
                        value = countdownMinutes,
                        onValueChange = { value -> countdownMinutes = value.filter { it.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.field_countdown_minutes)) },
                        singleLine = true
                    )
                }
                if (type == TaskTypes.TIME_WINDOW) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startTime,
                            onValueChange = { startTime = it.take(5) },
                            label = { Text(stringResource(R.string.field_start_time)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endTime,
                            onValueChange = { endTime = it.take(5) },
                            label = { Text(stringResource(R.string.field_end_time)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!timeWindowInputValid) {
                        Text(
                            stringResource(R.string.field_time_validation),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = timeWindowInputValid,
                onClick = {
                    onCreate(
                        name,
                        type,
                        countdownMinutes.toLongOrNull(),
                        startTime,
                        endTime
                    )
                }
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun EmptyState(onCreate: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp)) {
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

@Composable
private fun typeLabel(type: String): String = when (type) {
    TaskTypes.COUNT_DOWN -> stringResource(R.string.task_type_count_down)
    TaskTypes.TIME_WINDOW -> stringResource(R.string.task_type_time_window)
    else -> stringResource(R.string.task_type_count_up)
}

private fun isValidClockTime(value: String): Boolean {
    val parts = value.trim().split(":")
    if (parts.size != 2) return false
    val hour = parts[0].toIntOrNull() ?: return false
    val minute = parts[1].toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}

@Composable
private fun compactDurationString(millis: Long): String {
    val totalMinutes = max(0L, millis) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        hours > 0L -> stringResource(R.string.duration_hours_only, hours)
        else -> stringResource(R.string.duration_minutes_only, minutes)
    }
}
