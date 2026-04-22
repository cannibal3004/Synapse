package com.aiassistant.data.database

import androidx.room.*
import com.aiassistant.data.model.TaskExecutionHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskExecutionHistoryDao {
    @Insert
    suspend fun insert(history: TaskExecutionHistoryEntity)

    @Query("SELECT * FROM task_execution_history WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun getHistoryForTask(taskId: String, limit: Int = 50, offset: Int = 0): Flow<List<TaskExecutionHistoryEntity>>

    @Query("SELECT * FROM task_execution_history WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastExecution(taskId: String): TaskExecutionHistoryEntity?

    @Query("SELECT * FROM task_execution_history WHERE id = :id LIMIT 1")
    fun getHistoryById(id: String): Flow<TaskExecutionHistoryEntity?>

    @Query("SELECT * FROM task_execution_history WHERE taskId = :taskId ORDER BY createdAt DESC")
    fun getAllHistoryForTask(taskId: String): Flow<List<TaskExecutionHistoryEntity>>

    @Query("SELECT COUNT(*) FROM task_execution_history WHERE taskId = :taskId")
    suspend fun getExecutionCount(taskId: String): Int

    @Delete
    suspend fun delete(history: TaskExecutionHistoryEntity)

    @Query("DELETE FROM task_execution_history WHERE taskId = :taskId")
    suspend fun deleteHistoryForTask(taskId: String)

    @Query("DELETE FROM task_execution_history WHERE createdAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}
