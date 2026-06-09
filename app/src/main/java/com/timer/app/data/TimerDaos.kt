package com.timer.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: TaskCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<TaskCategoryEntity>)

    @Query("SELECT * FROM task_category WHERE archived = 0 ORDER BY name ASC")
    fun observeActive(): Flow<List<TaskCategoryEntity>>

    @Query("SELECT * FROM task_category ORDER BY name ASC")
    suspend fun getAll(): List<TaskCategoryEntity>

    @Query("SELECT * FROM task_category WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskCategoryEntity?

    @Query("SELECT * FROM task_category WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TaskCategoryEntity?
}

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(goals: List<GoalEntity>)

    @Query("SELECT * FROM goal WHERE active = 1 ORDER BY createdAtEpochMillis DESC")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goal ORDER BY createdAtEpochMillis DESC")
    suspend fun getAll(): List<GoalEntity>
}

@Dao
interface TaskTemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: TaskTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(templates: List<TaskTemplateEntity>)

    @Query("SELECT * FROM task_template WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskTemplateEntity?

    @Query("SELECT * FROM task_template WHERE archived = 0 ORDER BY updatedAtEpochMillis DESC")
    fun observeActiveTemplates(): Flow<List<TaskTemplateEntity>>

    @Query("SELECT * FROM task_template WHERE archived = 0 ORDER BY updatedAtEpochMillis DESC")
    suspend fun getActiveTemplates(): List<TaskTemplateEntity>

    @Query("SELECT * FROM task_template ORDER BY updatedAtEpochMillis DESC")
    suspend fun getAll(): List<TaskTemplateEntity>
}

@Dao
interface TaskInstanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(instance: TaskInstanceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(instances: List<TaskInstanceEntity>)

    @Query("SELECT * FROM task_instance WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskInstanceEntity?

    @Query("SELECT * FROM task_instance WHERE templateId = :templateId AND localDate = :localDate LIMIT 1")
    suspend fun getByTemplateAndDate(templateId: String, localDate: String): TaskInstanceEntity?

    @Query("SELECT * FROM task_instance WHERE templateId = :templateId ORDER BY localDate ASC, sortOrder DESC, createdAtEpochMillis DESC")
    suspend fun getByTemplate(templateId: String): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance WHERE localDate = :localDate AND archived = 0 ORDER BY sortOrder DESC, createdAtEpochMillis DESC")
    fun observeForDate(localDate: String): Flow<List<TaskInstanceEntity>>

    @Query("SELECT * FROM task_instance WHERE localDate = :localDate ORDER BY sortOrder DESC, createdAtEpochMillis DESC")
    suspend fun getForDate(localDate: String): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance WHERE status IN (:statuses)")
    suspend fun getByStatuses(statuses: List<String>): List<TaskInstanceEntity>

    @Query(
        """
        SELECT * FROM task_instance
        WHERE archived = 0
          AND type = :type
          AND status IN (:statuses)
          AND plannedEndEpochMillis IS NOT NULL
          AND plannedEndEpochMillis <= :nowEpochMillis
        """
    )
    suspend fun getExpiredTimeWindows(type: String, statuses: List<String>, nowEpochMillis: Long): List<TaskInstanceEntity>

    @Query(
        """
        SELECT * FROM task_instance
        WHERE archived = 0
          AND type = :type
          AND status = :status
          AND plannedStartEpochMillis IS NOT NULL
          AND plannedStartEpochMillis <= :nowEpochMillis
          AND plannedEndEpochMillis IS NOT NULL
          AND plannedEndEpochMillis > :nowEpochMillis
        """
    )
    suspend fun getReadyTimeWindows(type: String, status: String, nowEpochMillis: Long): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance ORDER BY localDate DESC, sortOrder DESC, createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<TaskInstanceEntity>>

    @Query("SELECT * FROM task_instance ORDER BY localDate DESC, sortOrder DESC, createdAtEpochMillis DESC")
    suspend fun getAll(): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instance WHERE localDate BETWEEN :startDate AND :endDate ORDER BY localDate ASC, sortOrder DESC, createdAtEpochMillis DESC")
    suspend fun getForDateRange(startDate: String, endDate: String): List<TaskInstanceEntity>
}

@Dao
interface TaskRuntimeStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: TaskRuntimeStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<TaskRuntimeStateEntity>)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<TaskSessionEntity>)

    @Query("SELECT * FROM task_session ORDER BY startedAtEpochMillis DESC")
    fun observeAll(): Flow<List<TaskSessionEntity>>

    @Query("SELECT * FROM task_session ORDER BY startedAtEpochMillis DESC")
    suspend fun getAll(): List<TaskSessionEntity>

    @Query("SELECT * FROM task_session WHERE templateId = :templateId ORDER BY startedAtEpochMillis DESC")
    suspend fun getByTemplate(templateId: String): List<TaskSessionEntity>
}

@Dao
interface TaskEventLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: TaskEventLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<TaskEventLogEntity>)

    @Query("SELECT * FROM task_event_log ORDER BY atEpochMillis DESC")
    fun observeAll(): Flow<List<TaskEventLogEntity>>

    @Query("SELECT * FROM task_event_log ORDER BY atEpochMillis DESC")
    suspend fun getAll(): List<TaskEventLogEntity>

    @Query("SELECT * FROM task_event_log WHERE instanceId = :instanceId ORDER BY atEpochMillis DESC LIMIT :limit")
    suspend fun recentForInstance(instanceId: String, limit: Int = 50): List<TaskEventLogEntity>
}
