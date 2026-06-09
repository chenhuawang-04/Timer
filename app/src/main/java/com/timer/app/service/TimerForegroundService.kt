package com.timer.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.timer.app.notification.TimerNotificationController
import com.timer.app.timerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TimerForegroundServiceStartResult {
    data object Started : TimerForegroundServiceStartResult
    data class Failed(val reason: String) : TimerForegroundServiceStartResult
}

class TimerForegroundService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private var monitorJob: Job? = null
    private val app by lazy { timerApplication() }
    private val repository by lazy { app.container.repository }
    private val notifications by lazy { app.container.notificationController }

    override fun onCreate() {
        super.onCreate()
        notifications.ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            TimerNotificationController.FOREGROUND_NOTIFICATION_ID,
            notifications.buildBootstrapNotification()
        )
        when (intent?.action) {
            ACTION_PAUSE_INSTANCE -> intent.getStringExtra(EXTRA_INSTANCE_ID)?.let { instanceId ->
                serviceScope.launch { repository.pauseInstance(instanceId) }
            }
            ACTION_CANCEL_INSTANCE -> intent.getStringExtra(EXTRA_INSTANCE_ID)?.let { instanceId ->
                serviceScope.launch { repository.cancelInstance(instanceId) }
            }
        }
        ensureMonitorRunning()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun ensureMonitorRunning() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                val result = repository.reconcileDeadlines()
                result.completedCountdowns.forEach { notifications.showCountdownCompleted(it) }
                result.missedTimeWindows.forEach { notifications.showTimeWindowMissed(it) }
                val running = repository.getRunningTimedTasksWithStates()
                if (running.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                    break
                } else {
                    val notification = notifications.buildForegroundNotification(running)
                    if (notifications.canPostNotifications()) {
                        NotificationManagerCompat.from(this@TimerForegroundService).notify(
                            TimerNotificationController.FOREGROUND_NOTIFICATION_ID,
                            notification
                        )
                    }
                }
                delay(if (running.any { it.instance.type == com.timer.app.data.TaskTypes.COUNT_DOWN }) 1_000L else 15_000L)
            }
        }
    }

    private suspend fun stopForegroundCompat() = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.timer.app.action.START_FOREGROUND"
        const val ACTION_PAUSE_INSTANCE = "com.timer.app.action.PAUSE_INSTANCE"
        const val ACTION_CANCEL_INSTANCE = "com.timer.app.action.CANCEL_INSTANCE"
        const val EXTRA_INSTANCE_ID = "instance_id"
        private const val TAG = "TimerForegroundSvc"

        fun start(context: Context): TimerForegroundServiceStartResult {
            val intent = Intent(context, TimerForegroundService::class.java).setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
                TimerForegroundServiceStartResult.Started
            } catch (error: RuntimeException) {
                val reason = error.javaClass.simpleName ?: "RuntimeException"
                Log.w(TAG, "Failed to start timer foreground service: $reason", error)
                TimerForegroundServiceStartResult.Failed(reason)
            }
        }
    }
}
