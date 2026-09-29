package com.personalai.assistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** A category the user gave a phone number (spam, important, ...). One row per number. */
@Entity(tableName = "caller_categories", indices = [Index(value = ["numberKey"], unique = true)])
data class CallerCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [com.personalai.assistant.core.PhoneNumbers.matchKey] of the number. */
    val numberKey: String,
    val displayNumber: String,
    /** A [com.personalai.assistant.core.CallerCategory] name. */
    val category: String,
    val note: String?,
    val createdAt: Long,
)

/** A call-screening rule. Conditions are stored with [com.personalai.assistant.core.ConditionCodec]. */
@Entity(tableName = "call_rules")
data class CallRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val matchMode: String,
    val conditions: String,
    val action: String,
    val notify: Boolean,
    val builtIn: Boolean,
    val createdAt: Long,
)

/** One incoming call the screening service handled, and why. */
@Entity(tableName = "screened_calls")
data class ScreenedCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** Null when the caller hid their number. */
    val number: String?,
    val contactName: String?,
    val category: String?,
    /** A [com.personalai.assistant.core.ScreeningAction] name. */
    val decision: String,
    val notified: Boolean,
    val ruleName: String?,
    val reason: String,
)

@Dao
interface CallerCategoryDao {
    @Query("SELECT * FROM caller_categories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CallerCategoryEntity>>

    @Query("SELECT * FROM caller_categories ORDER BY createdAt DESC")
    suspend fun all(): List<CallerCategoryEntity>

    @Query("SELECT * FROM caller_categories WHERE numberKey = :key LIMIT 1")
    suspend fun byKey(key: String): CallerCategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CallerCategoryEntity): Long

    @Query("DELETE FROM caller_categories WHERE numberKey = :key")
    suspend fun deleteByKey(key: String): Int
}

@Dao
interface CallRuleDao {
    @Query("SELECT * FROM call_rules ORDER BY priority, id")
    fun observeAll(): Flow<List<CallRuleEntity>>

    @Query("SELECT * FROM call_rules ORDER BY priority, id")
    suspend fun all(): List<CallRuleEntity>

    @Query("SELECT COUNT(*) FROM call_rules")
    suspend fun count(): Int

    @Insert
    suspend fun insert(rule: CallRuleEntity): Long

    @Insert
    suspend fun insertAll(rules: List<CallRuleEntity>)

    @Update
    suspend fun update(rule: CallRuleEntity)

    @Query("DELETE FROM call_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScreenedCallDao {
    @Query("SELECT * FROM screened_calls ORDER BY timestamp DESC LIMIT 300")
    fun observeRecent(): Flow<List<ScreenedCallEntity>>

    @Query("SELECT * FROM screened_calls WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun since(since: Long, limit: Int): List<ScreenedCallEntity>

    @Insert
    suspend fun insert(call: ScreenedCallEntity): Long

    @Query("DELETE FROM screened_calls")
    suspend fun deleteAll()
}

@Database(
    entities = [
        MemoryEntity::class,
        ActionLogEntity::class,
        CallerCategoryEntity::class,
        CallRuleEntity::class,
        ScreenedCallEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memories(): MemoryDao
    abstract fun actionLog(): ActionLogDao
    abstract fun callerCategories(): CallerCategoryDao
    abstract fun callRules(): CallRuleDao
    abstract fun screenedCalls(): ScreenedCallDao

    companion object {
        /** Version 2 adds call screening. Existing memories and activity are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `caller_categories` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `numberKey` TEXT NOT NULL, " +
                        "`displayNumber` TEXT NOT NULL, `category` TEXT NOT NULL, `note` TEXT, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_caller_categories_numberKey` ON `caller_categories` (`numberKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `call_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                        "`priority` INTEGER NOT NULL, `matchMode` TEXT NOT NULL, `conditions` TEXT NOT NULL, " +
                        "`action` TEXT NOT NULL, `notify` INTEGER NOT NULL, `builtIn` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `screened_calls` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `number` TEXT, " +
                        "`contactName` TEXT, `category` TEXT, `decision` TEXT NOT NULL, `notified` INTEGER NOT NULL, " +
                        "`ruleName` TEXT, `reason` TEXT NOT NULL)",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "assistant.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
