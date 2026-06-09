package com.timer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timer.app.ui.TimerDashboardScreen
import com.timer.app.ui.TimerViewModel
import com.timer.app.ui.TimerViewModelFactory
import com.timer.app.ui.theme.TimerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TimerViewModel by viewModels {
        TimerViewModelFactory(
            appContext = applicationContext,
            repository = (application as TimerApplication).container.repository,
            deadlineAlarmScheduler = (application as TimerApplication).container.deadlineAlarmScheduler
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TimerTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                var permissionGranted by remember { mutableStateOf(hasNotificationPermission()) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    permissionGranted = granted
                    viewModel.setNotificationPermissionGranted(granted)
                }
                LaunchedEffect(permissionGranted) {
                    viewModel.setNotificationPermissionGranted(permissionGranted)
                }
                TimerDashboardScreen(
                    uiState = uiState.copy(notificationPermissionGranted = permissionGranted),
                    onCreateTask = viewModel::createTask,
                    onStart = viewModel::startTask,
                    onPause = viewModel::pauseTask,
                    onResume = viewModel::resumeTask,
                    onComplete = viewModel::completeTask,
                    onCancel = viewModel::cancelTask,
                    onArchive = viewModel::archiveTask,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }
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
}
