package com.timer.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timer.app.R
import com.timer.app.data.AppPreferencesRepository
import com.timer.app.data.RoomTimerRepository
import com.timer.app.data.TimerDatabase
import com.timer.app.domain.AndroidTimerClock
import com.timer.app.domain.UuidIdProvider
import com.timer.app.notification.TimerNotificationController
import com.timer.app.scheduler.DeadlineAlarmScheduler
import com.timer.app.sync.CloudSyncCoordinator
import com.timer.app.sync.CloudSyncNetworkMonitor
import com.timer.app.sync.CloudSyncScheduler
import com.timer.app.sync.CloudSyncSecretStore
import com.timer.app.widget.TimerWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TimerApplication : Application() {
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var container: AppContainer
        private set

    private val _pendingRecoveryEvent = MutableStateFlow<RecoveryEvent?>(null)
    val pendingRecoveryEvent: StateFlow<RecoveryEvent?> = _pendingRecoveryEvent.asStateFlow()

    fun clearPendingRecoveryEvent() {
        _pendingRecoveryEvent.value = null
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, applicationScope)
        container.notificationController.ensureNotificationChannels()
        applicationScope.launch {
            try {
                // Check for interrupted tasks first
                val recoveryEvent = container.interruptionCoordinator.detectInterruptedTasks()
                if (recoveryEvent.interruptedTasks.isNotEmpty()) {
                    // Store recovery event for UI to handle
                    _pendingRecoveryEvent.value = recoveryEvent
                }
                container.automationCoordinator.warmUp()
            } finally {
                container.cloudSyncCoordinator.refreshScheduleFromStoredPreferences()
                container.cloudSyncCoordinator.requestAppLaunchCatchUp()
            }
        }
    }
}

class AppContainer(
    context: Context,
    private val applicationScope: CoroutineScope
) {
    private val appContext = context.applicationContext

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            try {
                // Add new columns for break reminder functionality with explicit NULL default
                database.execSQL(
                    "ALTER TABLE task_runtime_state ADD COLUMN lastBreakReminderAtEpochMillis INTEGER DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE task_runtime_state ADD COLUMN breakUntilEpochMillis INTEGER DEFAULT NULL"
                )
                android.util.Log.i("TimerDatabase", "Migration 4->5 completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("TimerDatabase", "Migration 4->5 failed", e)
                throw e
            }
        }
    }

    val database: TimerDatabase = Room.databaseBuilder(
        appContext,
        TimerDatabase::class.java,
        "timer.db"
    )
        .addMigrations(MIGRATION_4_5)
        .fallbackToDestructiveMigrationOnDowngrade()  // Allow downgrade but not upgrade failure
        .build()

    val preferencesRepository: AppPreferencesRepository = AppPreferencesRepository(appContext)

    val notificationController: TimerNotificationController = TimerNotificationController(appContext)

    val deadlineAlarmScheduler: DeadlineAlarmScheduler = DeadlineAlarmScheduler(appContext)

    // Shared clock instance to avoid drift between components
    private val sharedClock = AndroidTimerClock()

    val repository: RoomTimerRepository = RoomTimerRepository(
        database = database,
        clock = sharedClock,
        idProvider = UuidIdProvider(),
        untitledTaskName = appContext.getString(R.string.task_name_untitled),
        defaultCategories = listOf(
            appContext.getString(R.string.default_category_work) to 0xFF2563EB,
            appContext.getString(R.string.default_category_study) to 0xFF7C3AED,
            appContext.getString(R.string.default_category_health) to 0xFF0F766E,
            appContext.getString(R.string.default_category_life) to 0xFFF97316
        )
    )

    val widgetUpdater: TimerWidgetUpdater = TimerWidgetUpdater(appContext)

    val cloudSyncSecretStore: CloudSyncSecretStore = CloudSyncSecretStore(appContext)

    val cloudSyncScheduler: CloudSyncScheduler = CloudSyncScheduler(appContext)

    val cloudSyncNetworkMonitor: CloudSyncNetworkMonitor = CloudSyncNetworkMonitor(appContext)

    val cloudSyncCoordinator: CloudSyncCoordinator = CloudSyncCoordinator(
        appContext = appContext,
        applicationScope = applicationScope,
        repository = repository,
        preferencesRepository = preferencesRepository,
        secretStore = cloudSyncSecretStore,
        scheduler = cloudSyncScheduler,
        networkMonitor = cloudSyncNetworkMonitor
    )

    val automationCoordinator: TimerAutomationCoordinator = TimerAutomationCoordinator(
        context = appContext,
        repository = repository,
        notifications = notificationController,
        alarmScheduler = deadlineAlarmScheduler,
        widgetUpdater = widgetUpdater
    )

    val interruptionCoordinator: TimerInterruptionCoordinator = TimerInterruptionCoordinator(
        context = appContext,
        repository = repository,
        preferencesRepository = preferencesRepository,
        clock = sharedClock  // Use same clock instance
    )
}

fun Context.timerApplication(): TimerApplication = applicationContext as TimerApplication
