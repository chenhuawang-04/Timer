package com.timer.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.timer.app.R
import com.timer.app.data.AppPreferencesRepository
import com.timer.app.data.RoomTimerRepository
import com.timer.app.data.TimerDatabase
import com.timer.app.domain.AndroidTimerClock
import com.timer.app.domain.UuidIdProvider
import com.timer.app.notification.TimerNotificationController
import com.timer.app.scheduler.DeadlineAlarmScheduler
import com.timer.app.widget.TimerWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TimerApplication : Application() {
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notificationController.ensureNotificationChannels()
        applicationScope.launch {
            container.automationCoordinator.warmUp()
        }
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: TimerDatabase = Room.databaseBuilder(
        appContext,
        TimerDatabase::class.java,
        "timer.db"
    ).fallbackToDestructiveMigration().build()

    val preferencesRepository: AppPreferencesRepository = AppPreferencesRepository(appContext)

    val notificationController: TimerNotificationController = TimerNotificationController(appContext)

    val deadlineAlarmScheduler: DeadlineAlarmScheduler = DeadlineAlarmScheduler(appContext)

    val repository: RoomTimerRepository = RoomTimerRepository(
        database = database,
        clock = AndroidTimerClock(),
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

    val automationCoordinator: TimerAutomationCoordinator = TimerAutomationCoordinator(
        context = appContext,
        repository = repository,
        notifications = notificationController,
        alarmScheduler = deadlineAlarmScheduler,
        widgetUpdater = widgetUpdater
    )
}

fun Context.timerApplication(): TimerApplication = applicationContext as TimerApplication
