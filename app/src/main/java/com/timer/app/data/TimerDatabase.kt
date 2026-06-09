package com.timer.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskTemplateEntity::class,
        TaskInstanceEntity::class,
        TaskRuntimeStateEntity::class,
        TaskSessionEntity::class,
        TaskEventLogEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class TimerDatabase : RoomDatabase() {
    abstract fun templateDao(): TaskTemplateDao
    abstract fun instanceDao(): TaskInstanceDao
    abstract fun runtimeStateDao(): TaskRuntimeStateDao
    abstract fun sessionDao(): TaskSessionDao
    abstract fun eventLogDao(): TaskEventLogDao
}
