@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.timer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.timer.app.R
import com.timer.app.sync.CloudSyncConfiguration
import com.timer.app.sync.CloudSyncPreferencesSnapshot
import com.timer.app.sync.CloudSyncProviders
import com.timer.app.sync.CloudSyncResultCodes
import com.timer.app.sync.CloudSyncSettingsDraft
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CloudSyncSettingsUiState(
    val preferences: CloudSyncPreferencesSnapshot = CloudSyncPreferencesSnapshot(),
    val hasToken: Boolean = false,
    val isBusy: Boolean = false
) {
    val isConfigured: Boolean
        get() = preferences.configuration.isComplete(hasToken)
}

@Composable
fun CloudSyncSettingsSection(
    state: CloudSyncSettingsUiState,
    onSave: (CloudSyncSettingsDraft) -> Unit,
    onClearToken: () -> Unit,
    onSyncNow: () -> Unit,
    onRestoreLatest: () -> Unit
) {
    var provider by remember(state.preferences.configuration) { mutableStateOf(state.preferences.configuration.provider) }
    var owner by remember(state.preferences.configuration) { mutableStateOf(state.preferences.configuration.repositoryOwner) }
    var repositoryName by remember(state.preferences.configuration) { mutableStateOf(state.preferences.configuration.repositoryName) }
    var branch by remember(state.preferences.configuration) { mutableStateOf(state.preferences.configuration.branch) }
    var basePath by remember(state.preferences.configuration) { mutableStateOf(state.preferences.configuration.basePath) }
    var autoSyncEnabled by remember(state.preferences.configuration) { mutableStateOf(state.preferences.configuration.autoSyncEnabled) }
    var wifiOnly by remember(state.preferences.configuration) { mutableStateOf(state.preferences.configuration.wifiOnly) }
    var tokenInput by remember { mutableStateOf("") }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.preferences.configuration) {
        val configuration: CloudSyncConfiguration = state.preferences.configuration
        provider = configuration.provider
        owner = configuration.repositoryOwner
        repositoryName = configuration.repositoryName
        branch = configuration.branch
        basePath = configuration.basePath
        autoSyncEnabled = configuration.autoSyncEnabled
        wifiOnly = configuration.wifiOnly
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.cloud_sync_description), style = MaterialTheme.typography.bodySmall)
        ChoiceRow(
            label = stringResource(R.string.cloud_sync_provider_label),
            options = listOf(
                CloudSyncProviders.GITEE to stringResource(R.string.cloud_sync_provider_gitee),
                CloudSyncProviders.GITHUB to stringResource(R.string.cloud_sync_provider_github)
            ),
            selected = provider,
            onSelect = { provider = it }
        )
        Text(
            text = if (provider == CloudSyncProviders.GITEE) {
                stringResource(R.string.cloud_sync_provider_hint_gitee)
            } else {
                stringResource(R.string.cloud_sync_provider_hint_github)
            },
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = owner,
            onValueChange = { owner = it },
            label = { Text(stringResource(R.string.cloud_sync_owner_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = repositoryName,
            onValueChange = { repositoryName = it },
            label = { Text(stringResource(R.string.cloud_sync_repository_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = branch,
                onValueChange = { branch = it },
                label = { Text(stringResource(R.string.cloud_sync_branch_label)) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = basePath,
                onValueChange = { basePath = it },
                label = { Text(stringResource(R.string.cloud_sync_base_path_label)) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        ToggleSettingRow(
            label = stringResource(R.string.cloud_sync_auto_daily_label),
            checked = autoSyncEnabled,
            onCheckedChange = { autoSyncEnabled = it }
        )
        ToggleSettingRow(
            label = stringResource(R.string.cloud_sync_wifi_only_label),
            checked = wifiOnly,
            onCheckedChange = { wifiOnly = it }
        )
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text(stringResource(R.string.cloud_sync_token_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    if (state.hasToken) {
                        stringResource(R.string.cloud_sync_token_saved)
                    } else {
                        stringResource(R.string.cloud_sync_token_missing)
                    }
                )
            }
        )
        StatusSummary(state = state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !state.isBusy,
                onClick = {
                    onSave(
                        CloudSyncSettingsDraft(
                            autoSyncEnabled = autoSyncEnabled,
                            provider = provider,
                            repositoryOwner = owner,
                            repositoryName = repositoryName,
                            branch = branch,
                            basePath = basePath,
                            wifiOnly = wifiOnly,
                            tokenInput = tokenInput.ifBlank { null }
                        )
                    )
                    tokenInput = ""
                }
            ) {
                Text(stringResource(R.string.cloud_sync_save_settings))
            }
            OutlinedButton(
                enabled = !state.isBusy && state.hasToken,
                onClick = onClearToken
            ) {
                Text(stringResource(R.string.cloud_sync_clear_token))
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = !state.isBusy && state.isConfigured,
                onClick = onSyncNow
            ) {
                Text(stringResource(R.string.cloud_sync_action_sync_now))
            }
            OutlinedButton(
                enabled = !state.isBusy && state.isConfigured,
                onClick = { showRestoreConfirm = true }
            ) {
                Text(stringResource(R.string.cloud_sync_action_restore_latest))
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.cloud_sync_restore_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_sync_restore_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirm = false
                        onRestoreLatest()
                    }
                ) {
                    Text(stringResource(R.string.cloud_sync_restore_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun StatusSummary(state: CloudSyncSettingsUiState) {
    val status = state.preferences.status
    val statusLabel = when (status.lastResultCode) {
        CloudSyncResultCodes.SUCCESS -> stringResource(R.string.cloud_sync_result_success)
        CloudSyncResultCodes.SKIPPED -> stringResource(R.string.cloud_sync_result_skipped)
        CloudSyncResultCodes.FAILED -> stringResource(R.string.cloud_sync_result_failed)
        CloudSyncResultCodes.RESTORED -> stringResource(R.string.cloud_sync_result_restored)
        CloudSyncResultCodes.INCOMPLETE -> stringResource(R.string.cloud_sync_result_incomplete)
        else -> stringResource(R.string.cloud_sync_result_idle)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            stringResource(
                R.string.cloud_sync_summary_status,
                statusLabel
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            stringResource(
                R.string.cloud_sync_summary_last_attempt,
                status.lastAttemptAtEpochMillis.formatAsLocalTime()
            ),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(
                R.string.cloud_sync_summary_last_success,
                status.lastSuccessAtEpochMillis.formatAsLocalTime()
            ),
            style = MaterialTheme.typography.bodySmall
        )
        status.lastMessage?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
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
private fun ToggleSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun Long?.formatAsLocalTime(): String {
    if (this == null) return "—"
    return Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()))
}



