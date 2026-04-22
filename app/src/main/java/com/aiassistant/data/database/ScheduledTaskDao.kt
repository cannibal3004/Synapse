package com.aiassistant.data.database

import androidx.room.*
import com.aiassistant.data.model.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ScheduledTaskEntity): Long

    @Update
    suspend fun updateTask(task: ScheduledTaskEntity)

    @Delete
    suspend fun deleteTask(task: ScheduledTaskEntity)

    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getTaskById(id: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE isEnabled = 1 AND nextRunAt <= :now ORDER BY nextRunAt ASC")
    fun getPendingTasks(now: Long = System.currentTimeMillis()): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE isEnabled = 1 AND nextRunAt <= :now LIMIT 1")
    suspend fun getEarliestPendingTask(now: Long = System.currentTimeMillis()): ScheduledTaskEntity?

    @Query("UPDATE scheduled_tasks SET isEnabled = :isEnabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun toggleTask(id: String, isEnabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE scheduled_tasks SET lastRunAt = :lastRunAt, runCount = runCount + 1, nextRunAt = :nextRunAt, lastError = :lastError, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTaskRunState(id: String, lastRunAt: Long, nextRunAt: Long, lastError: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String)
}
