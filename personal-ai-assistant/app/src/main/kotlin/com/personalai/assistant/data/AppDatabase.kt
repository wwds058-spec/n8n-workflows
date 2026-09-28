package com.personalai.assistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.personalai.assistant.core.MemoryFact
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val person: String?,
    val relationship: String?,
    val createdAt: Long,
) {
    fun toFact() = MemoryFact(id, text, person, relationship)
}

@Entity(tableName = "action_log")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val toolName: String,
    val summary: String,
    val status: String,
    val result: String,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: Long): MemoryEntity?

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_log ORDER BY timestamp DESC LIMIT 300")
    fun observeRecent(): Flow<List<ActionLogEntity>>

    @Insert
    suspend fun insert(entry: ActionLogEntity): Long

    @Query("DELETE FROM action_log")
    suspend fun deleteAll()
}

@Database(entities = [MemoryEntity::class, ActionLogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memories(): MemoryDao
    abstract fun actionLog(): ActionLogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "assistant.db").build()
    }
}
