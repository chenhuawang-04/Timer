package com.timer.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskCategoryEntity::class,
        GoalEntity::class,
        TaskTemplateEntity::class,
        TaskInstanceEntity::class,
        TaskRuntimeStateEntity::class,
        TaskSessionEntity::class,
        TaskEventLogEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class TimerDatabase : RoomDatabase() {
    abstract fun categoryDao(): TaskCategoryDao
    abstract fun goalDao(): GoalDao
    abstract fun templateDao(): TaskTemplateDao
    abstract fun instanceDao(): TaskInstanceDao
    abstract fun runtimeStateDao(): TaskRuntimeStateDao
    abstract fun sessionDao(): TaskSessionDao
    abstract fun eventLogDao(): TaskEventLogDao
}
