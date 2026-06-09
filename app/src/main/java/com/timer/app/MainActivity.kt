package com.timer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timer.app.ui.AppTab
import com.timer.app.ui.LaunchRequest
import com.timer.app.ui.TimerDashboardScreen
import com.timer.app.ui.TimerViewModel
import com.timer.app.ui.TimerViewModelFactory
import com.timer.app.ui.theme.TimerTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val launchRequests = MutableStateFlow<LaunchRequest?>(null)

    private val viewModel: TimerViewModel by viewModels {
        TimerViewModelFactory(
            appContext = applicationContext,
            container = (application as TimerApplication).container
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchRequests.value = intent.toLaunchRequest()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val launchRequest by launchRequests.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                viewModel.setNotificationPermissionGranted(granted)
            }
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                if (uri != null) {
                    viewModel.exportBackup(uri)
                }
            }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    viewModel.importBackup(uri)
                }
            }

            LaunchedEffect(Unit) {
                viewModel.setNotificationPermissionGranted(hasNotificationPermission())
            }
            LaunchedEffect(uiState.focusTask != null, uiState.appearance.keepScreenOnInFocus) {
                if (uiState.focusTask != null && uiState.appearance.keepScreenOnInFocus) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            TimerTheme(preferences = uiState.appearance) {
                TimerDashboardScreen(
                    uiState = uiState,
                    launchRequest = launchRequest,
                    onLaunchRequestHandled = { launchRequests.value = null },
                    onCreateTask = viewModel::createTask,
                    onCreateCategory = viewModel::createCategory,
                    onCreateGoal = viewModel::createGoal,
                    onCreateTodayFromRoutine = viewModel::createTodayFromRoutine,
                    onArchiveRoutine = viewModel::archiveRoutine,
                    onSelectTab = viewModel::selectTab,
                    onSelectDate = viewModel::selectDate,
                    onChangeMonth = viewModel::changeMonth,
                    onOpenFocus = viewModel::openFocus,
                    onCloseFocus = viewModel::closeFocus,
                    onStart = viewModel::startTask,
                    onPause = viewModel::pauseTask,
                    onResume = viewModel::resumeTask,
                    onComplete = viewModel::completeTask,
                    onCancel = viewModel::cancelTask,
                    onArchive = viewModel::archiveTask,
                    onSaveResultNote = viewModel::saveResultNote,
                    onUpdateThemeMode = viewModel::updateThemeMode,
                    onUpdateAccentPalette = viewModel::updateAccentPalette,
                    onUpdateDynamicColor = viewModel::updateDynamicColor,
                    onUpdateDashboardLayout = viewModel::updateDashboardLayout,
                    onUpdateSortMode = viewModel::updateSortMode,
                    onUpdateShowCompleted = viewModel::updateShowCompletedTasks,
                    onUpdateEnergyMode = viewModel::updateEnergyMode,
                    onUpdateKeepScreenOn = viewModel::updateKeepScreenOnInFocus,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onExportBackup = {
                        exportLauncher.launch("timer-backup-${System.currentTimeMillis()}.json")
                    },
                    onImportBackup = {
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                    cloudSyncState = uiState.cloudSync,
                    onSaveCloudSyncSettings = viewModel::saveCloudSyncSettings,
                    onClearCloudSyncToken = viewModel::clearCloudSyncToken,
                    onSyncCloudNow = viewModel::syncCloudNow,
                    onRestoreCloudLatest = viewModel::restoreCloudLatest,
                    onClearStatusMessage = viewModel::clearStatusMessage
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequests.value = intent.toLaunchRequest()
        if (intent.action == ACTION_OPEN_INSIGHTS) {
            viewModel.selectTab(AppTab.INSIGHTS)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setNotificationPermissionGranted(hasNotificationPermission())
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun Intent?.toLaunchRequest(): LaunchRequest? {
        val safeIntent = this ?: return null
        return when (safeIntent.action) {
            ACTION_CREATE_TASK_SHORTCUT -> LaunchRequest("CREATE_TASK")
            ACTION_CREATE_ROUTINE_SHORTCUT -> LaunchRequest("CREATE_ROUTINE")
            ACTION_OPEN_FOCUS -> LaunchRequest("OPEN_FOCUS")
            Intent.ACTION_SEND -> {
                val text = safeIntent.getStringExtra(Intent.EXTRA_TEXT)
                LaunchRequest("CREATE_TASK", sharedText = text)
            }
            else -> null
        }
    }

    companion object {
        const val ACTION_CREATE_TASK_SHORTCUT = "com.timer.app.action.CREATE_TASK_SHORTCUT"
        const val ACTION_CREATE_ROUTINE_SHORTCUT = "com.timer.app.action.CREATE_ROUTINE_SHORTCUT"
        const val ACTION_OPEN_FOCUS = "com.timer.app.action.OPEN_FOCUS"
        const val ACTION_OPEN_INSIGHTS = "com.timer.app.action.OPEN_INSIGHTS"
    }
}
