package com.timer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.timer.app.R
import com.timer.app.domain.DurationFormatter
import com.timer.app.domain.InterruptedTask

@Composable
fun RecoveryDialog(
    interruptedTasks: List<InterruptedTask>,
    onDismiss: () -> Unit,
    onRecoverActual: (String, Long) -> Unit,
    onRecoverRecorded: (String) -> Unit,
    onRecoverCustom: (String, Long) -> Unit
) {
    if (interruptedTasks.isEmpty()) return

    var currentIndex by remember { mutableStateOf(0) }
    var showCustomInput by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }

    val currentTask = interruptedTasks.getOrNull(currentIndex) ?: return

    // Defined before use: advances to the next interrupted task or dismisses the dialog.
    val moveToNextOrDismiss: () -> Unit = {
        showCustomInput = false
        customMinutes = ""
        if (currentIndex < interruptedTasks.size - 1) {
            currentIndex++
        } else {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.recovery_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(
                        R.string.recovery_dialog_message,
                        currentTask.instance.nameSnapshot,
                        DurationFormatter.clock(currentTask.recordedElapsedMillis),
                        DurationFormatter.clock(currentTask.expectedElapsedMillis),
                        DurationFormatter.clock(currentTask.discrepancyMillis)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )

                if (showCustomInput) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.recovery_custom_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Divider()

                // Action buttons
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!showCustomInput) {
                        Button(
                            onClick = {
                                onRecoverActual(currentTask.instance.id, currentTask.expectedElapsedMillis)
                                moveToNextOrDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.recovery_option_actual))
                        }

                        OutlinedButton(
                            onClick = {
                                onRecoverRecorded(currentTask.instance.id)
                                moveToNextOrDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.recovery_option_recorded))
                        }

                        OutlinedButton(
                            onClick = { showCustomInput = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.recovery_option_custom))
                        }
                    } else {
                        Button(
                            onClick = {
                                val minutes = customMinutes.toLongOrNull()
                                if (minutes != null && minutes > 0) {
                                    onRecoverCustom(currentTask.instance.id, minutes * 60_000L)
                                    moveToNextOrDismiss()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = customMinutes.toLongOrNull()?.let { it > 0 } == true
                        ) {
                            Text(stringResource(R.string.recovery_apply))
                        }

                        TextButton(
                            onClick = {
                                showCustomInput = false
                                customMinutes = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                }

                // Progress indicator
                if (interruptedTasks.size > 1) {
                    Text(
                        text = "${currentIndex + 1} / ${interruptedTasks.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
fun BreakReminderDialog(
    taskName: String,
    elapsedMinutes: Int,
    breakDurationMinutes: Int,
    onContinue: () -> Unit,
    onStop: () -> Unit,
    onTakeBreak: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.break_reminder_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = stringResource(R.string.break_reminder_message, elapsedMinutes),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = {
                    onContinue()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.break_reminder_continue))
                }
                TextButton(onClick = {
                    onStop()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.break_reminder_stop))
                }
                Button(onClick = {
                    onTakeBreak()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.break_reminder_take_break, breakDurationMinutes))
                }
            }
        }
    )
}
