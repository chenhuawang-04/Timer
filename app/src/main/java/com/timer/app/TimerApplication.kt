package com.timer.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.timer.app.R
import com.timer.app.data.RoomTimerRepository
import com.timer.app.data.TimerDatabase
import com.timer.app.domain.AndroidTimerClock
import com.timer.app.domain.UuidIdProvider
import com.timer.app.notification.TimerNotificationController
import com.timer.app.scheduler.DeadlineAlarmScheduler
import com.timer.app.service.TimerForegroundService
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
            val result = container.repository.reconcileDeadlines()
            result.completedCountdowns.forEach { container.notificationController.showCountdownCompleted(it) }
            result.missedTimeWindows.forEach { container.notificationController.showTimeWindowMissed(it) }
            container.deadlineAlarmScheduler.scheduleFor(container.repository.getAllInstances())
            if (container.repository.getRunningTimedTasksWithStates().isNotEmpty()) {
                TimerForegroundService.start(this@TimerApplication)
            }
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

    val notificationController: TimerNotificationController = TimerNotificationController(appContext)

    val deadlineAlarmScheduler: DeadlineAlarmScheduler = DeadlineAlarmScheduler(appContext)

    val repository: RoomTimerRepository = RoomTimerRepository(
        database = database,
        clock = AndroidTimerClock(),
        idProvider = UuidIdProvider(),
        untitledTaskName = appContext.getString(R.string.task_name_untitled)
    )
}

fun Context.timerApplication(): TimerApplication = applicationContext as TimerApplication
