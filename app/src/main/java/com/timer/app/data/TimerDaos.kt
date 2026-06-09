package com.timer.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskTemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: TaskTemplateEntity)

    @Query("SELECT * FROM task_template WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskTemplateEntity?

    @Query("SELECT * FROM task_template WHERE archived = 0 ORDER BY createdAtEpochMillis DESC")
    fun observeActiveTemplates(): Flow<List<TaskTemplateEntity>>
}

@Dao
interface TaskInstanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(instance: TaskInstanceEntity)

    @Query("SELECT * FROM task_instance WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskInstanceEntity?

    @Query("SELECT * FROM task_instance WHERE localDate = :localDate AND archived = 0 ORDER BY createdAtEpochMillis DESC")
    fun observeForDate(localDate: String): Flow<List<TaskInstanceEntity>>

    @Query("SELECT * FROM task_instance WHERE localDate = :localDate AND archived = 0 ORDER BY createdAtEpochMillis DESC")
    suspend fun getForDate(localDate: String): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance WHERE status IN (:statuses)")
    suspend fun getByStatuses(statuses: List<String>): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance WHERE archived = 0 AND type = :type AND status IN (:statuses) AND plannedEndEpochMillis IS NOT NULL AND plannedEndEpochMillis <= :nowEpochMillis")
    suspend fun getExpiredTimeWindows(type: String, statuses: List<String>, nowEpochMillis: Long): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance WHERE archived = 0 AND type = :type AND status = :status AND plannedStartEpochMillis IS NOT NULL AND plannedStartEpochMillis <= :nowEpochMillis AND plannedEndEpochMillis IS NOT NULL AND plannedEndEpochMillis > :nowEpochMillis")
    suspend fun getReadyTimeWindows(type: String, status: String, nowEpochMillis: Long): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<TaskInstanceEntity>>

    @Query("SELECT * FROM task_instance ORDER BY createdAtEpochMillis DESC")
    suspend fun getAll(): List<TaskInstanceEntity>
}

@Dao
interface TaskRuntimeStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: TaskRuntimeStateEntity)

    @Query("SELECT * FROM task_runtime_state WHERE instanceId = :instanceId LIMIT 1")
    suspend fun getByInstanceId(instanceId: String): TaskRuntimeStateEntity?

    @Query("SELECT * FROM task_runtime_state")
    fun observeAll(): Flow<List<TaskRuntimeStateEntity>>

    @Query("SELECT * FROM task_runtime_state")
    suspend fun getAll(): List<TaskRuntimeStateEntity>

    @Query("SELECT * FROM task_runtime_state WHERE status = :status")
    suspend fun getByStatus(status: String): List<TaskRuntimeStateEntity>
}

@Dao
interface TaskSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: TaskSessionEntity)

    @Query("SELECT * FROM task_session ORDER BY startedAtEpochMillis DESC")
    fun observeAll(): Flow<List<TaskSessionEntity>>

    @Query("SELECT * FROM task_session ORDER BY startedAtEpochMillis DESC")
    suspend fun getAll(): List<TaskSessionEntity>
}

@Dao
interface TaskEventLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: TaskEventLogEntity)

    @Query("SELECT * FROM task_event_log WHERE instanceId = :instanceId ORDER BY atEpochMillis DESC LIMIT :limit")
    suspend fun recentForInstance(instanceId: String, limit: Int = 50): List<TaskEventLogEntity>
}
