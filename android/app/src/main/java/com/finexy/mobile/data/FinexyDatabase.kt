package com.finexy.mobile.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val localId: String,
    val serverId: Long? = null,
    val timeSequenceId: Long? = null,
    val type: Int,
    val sourceAccountId: Long = LOCAL_ACCOUNT_ID,
    val destinationAccountId: Long? = null,
    val categoryId: Long? = null,
    val categoryName: String,
    val sourceAmountMinor: Long,
    val destinationAmountMinor: Long = 0,
    val currency: String = "CNY",
    val comment: String,
    val time: Long,
    val utcOffset: Int = 480,
    val tagIdsJson: String = "[]",
    val syncState: String = SyncState.PENDING,
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncedSnapshotJson: String = "",
    @ColumnInfo(defaultValue = "'[]'") val pictureIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "''") val geoLocationJson: String = "",
    @ColumnInfo(defaultValue = "0") val hideAmount: Boolean = false
) {
    companion object {
        const val LOCAL_ACCOUNT_ID = -1L
    }
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val currency: String,
    val balanceMinor: Long = 0,
    val hidden: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val parentId: Long = 0,
    val category: Int = 1,
    val type: Int = 1,
    val icon: Long = 1,
    val color: String = "000000",
    val comment: String = "",
    val displayOrder: Int = 0,
    val creditCardStatementDate: Int = 0
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val parentId: Long = 0,
    val type: Int,
    val icon: Long = 0,
    val color: String = "",
    val hidden: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val comment: String = "",
    val displayOrder: Int = 0
)

@Entity(tableName = "category_mappings", primaryKeys = ["localName", "transactionType"])
data class CategoryMappingEntity(
    val localName: String,
    val transactionType: Int,
    val serverId: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "account_mappings")
data class AccountMappingEntity(
    @PrimaryKey val localId: Long,
    val serverId: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val groupId: Long = 0,
    val hidden: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "transaction_templates")
data class TemplateEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val type: Int,
    val categoryId: Long,
    val sourceAccountId: Long,
    val sourceAmountMinor: Long,
    val comment: String,
    val tagIdsJson: String = "[]",
    val hidden: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val templateType: Int = 1,
    val destinationAccountId: Long = 0,
    val destinationAmountMinor: Long = 0,
    val hideAmount: Boolean = false,
    val scheduledFrequencyType: Int? = null,
    val scheduledFrequency: String? = null,
    val scheduledStartDate: String? = null,
    val scheduledEndDate: String? = null,
    val utcOffset: Int? = null,
    val scheduledAt: Int? = null,
    val nextScheduledTime: Long? = null,
    val displayOrder: Int = 0,
    val pausedFromFrequencyType: Int? = null,
    val pausedFromFrequency: String? = null
)

@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey val localId: String,
    val serverId: Long,
    val localComment: String,
    val localAmountMinor: Long,
    val remoteComment: String,
    val remoteAmountMinor: Long,
    val remoteEntityJson: String = "",
    val resolution: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * One server-side review item of a due schedule. The composite key matches the
 * server's uniqueness rule; confirming posts the snapshot exactly once there.
 */
@Entity(tableName = "scheduled_occurrences", primaryKeys = ["templateId", "scheduledUnixTime"])
data class ScheduledOccurrenceEntity(
    val templateId: Long,
    val scheduledUnixTime: Long,
    val status: Int,
    val transactionId: Long = 0,
    val name: String = "",
    val type: Int = 0,
    val categoryId: Long = 0,
    val sourceAccountId: Long = 0,
    val destinationAccountId: Long = 0,
    val sourceAmountMinor: Long = 0,
    val destinationAmountMinor: Long = 0,
    val utcOffset: Int = 0,
    val hideAmount: Boolean = false,
    val tagIdsJson: String = "[]",
    val comment: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = 1
        const val STATUS_CONFIRMED = 2
        const val STATUS_DISMISSED = 3
    }
}

@Entity(tableName = "sync_status")
data class SyncStatusEntity(
    @PrimaryKey val id: Int = 1,
    val state: String = SyncRunState.IDLE,
    val message: String = "尚未同步",
    val attemptCount: Int = 0,
    val nextRetryAt: Long = 0,
    val lastStartedAt: Long = 0,
    val lastFinishedAt: Long = 0
)

object SyncRunState {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val RETRYING = "retrying"
    const val FAILED = "failed"
    const val SUCCEEDED = "succeeded"
}

object SyncState {
    const val SYNCED = "synced"
    const val PENDING = "pending"
    const val FAILED = "failed"
    const val CONFLICT = "conflict"
}

@Dao
interface FinexyDao {
    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY time DESC, updatedAt DESC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY time DESC, updatedAt DESC")
    suspend fun allTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE localId = :localId LIMIT 1")
    suspend fun findTransaction(localId: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactions(items: List<TransactionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(item: TransactionEntity)

    @Query("UPDATE transactions SET deleted = 1, syncState = :syncState, updatedAt = :updatedAt WHERE localId = :localId")
    suspend fun markDeleted(localId: String, syncState: String = SyncState.PENDING, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM accounts ORDER BY hidden, displayOrder, name")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(items: List<AccountEntity>)

    @Query("SELECT * FROM accounts WHERE hidden = 0 ORDER BY id LIMIT 1")
    suspend fun firstVisibleAccount(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findAccount(id: Long): AccountEntity?

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: Long)

    @Query("DELETE FROM accounts WHERE parentId = :parentId AND id NOT IN (:keepIds)")
    suspend fun deleteMissingChildAccounts(parentId: Long, keepIds: List<Long>)

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findCategory(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY hidden, type, parentId, displayOrder, name")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(items: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :id OR parentId = :id")
    suspend fun deleteCategoryTree(id: Long)

    @Query("SELECT * FROM tags WHERE hidden = 0 ORDER BY groupId, name")
    fun observeTags(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(items: List<TagEntity>)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("SELECT * FROM transaction_templates WHERE hidden = 0 AND templateType = 1 ORDER BY name")
    fun observeTemplates(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM transaction_templates WHERE templateType = 2 ORDER BY displayOrder, name")
    fun observeScheduledTemplates(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM transaction_templates")
    suspend fun allTemplates(): List<TemplateEntity>

    @Query("SELECT * FROM scheduled_occurrences ORDER BY scheduledUnixTime, templateId")
    fun observeOccurrences(): Flow<List<ScheduledOccurrenceEntity>>

    @Query("SELECT * FROM scheduled_occurrences")
    suspend fun allOccurrences(): List<ScheduledOccurrenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrences(items: List<ScheduledOccurrenceEntity>)

    @Query("DELETE FROM scheduled_occurrences WHERE templateId || ':' || scheduledUnixTime NOT IN (:keys)")
    suspend fun deleteOccurrencesNotIn(keys: List<String>)

    @Query("DELETE FROM transaction_templates WHERE templateType = 2 AND id NOT IN (:ids)")
    suspend fun deleteMissingScheduledTemplates(ids: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplates(items: List<TemplateEntity>)

    @Query("DELETE FROM transaction_templates WHERE id = :id")
    suspend fun deleteTemplate(id: Long)

    @Query("SELECT * FROM categories WHERE name = :name AND hidden = 0 ORDER BY parentId")
    suspend fun findCategoriesByName(name: String): List<CategoryEntity>

    @Query("SELECT * FROM category_mappings ORDER BY transactionType, localName")
    fun observeCategoryMappings(): Flow<List<CategoryMappingEntity>>

    @Query("SELECT * FROM account_mappings ORDER BY localId")
    fun observeAccountMappings(): Flow<List<AccountMappingEntity>>

    @Query("SELECT * FROM account_mappings WHERE localId = :localId LIMIT 1")
    suspend fun findAccountMapping(localId: Long): AccountMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccountMapping(mapping: AccountMappingEntity)

    @Query("DELETE FROM account_mappings WHERE localId = :localId")
    suspend fun deleteAccountMapping(localId: Long)

    @Query("UPDATE transactions SET sourceAccountId = :serverId, currency = :currency, updatedAt = :updatedAt WHERE serverId IS NULL AND deleted = 0 AND sourceAccountId = :localId")
    suspend fun backfillPendingAccount(localId: Long, serverId: Long, currency: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM category_mappings WHERE localName = :localName AND transactionType = :transactionType LIMIT 1")
    suspend fun findCategoryMapping(localName: String, transactionType: Int): CategoryMappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryMapping(mapping: CategoryMappingEntity)

    @Query("DELETE FROM category_mappings WHERE localName = :localName AND transactionType = :transactionType")
    suspend fun deleteCategoryMapping(localName: String, transactionType: Int)

    @Query("UPDATE transactions SET categoryId = :serverId, updatedAt = :updatedAt WHERE serverId IS NULL AND deleted = 0 AND categoryId IS NULL AND categoryName = :localName AND type = :transactionType")
    suspend fun backfillPendingCategory(localName: String, transactionType: Int, serverId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET categoryId = NULL, updatedAt = :updatedAt WHERE serverId IS NULL AND deleted = 0 AND categoryId = :serverId AND categoryName = :localName AND type = :transactionType")
    suspend fun clearPendingCategory(localName: String, transactionType: Int, serverId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM sync_conflicts ORDER BY createdAt DESC")
    fun observeConflicts(): Flow<List<SyncConflictEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflict(conflict: SyncConflictEntity)

    @Query("SELECT * FROM sync_conflicts WHERE localId = :localId LIMIT 1")
    suspend fun findConflict(localId: String): SyncConflictEntity?

    @Query("DELETE FROM sync_conflicts WHERE localId = :localId")
    suspend fun deleteConflict(localId: String)

    @Query("SELECT * FROM sync_status WHERE id = 1")
    fun observeSyncStatus(): Flow<SyncStatusEntity?>

    @Query("SELECT * FROM sync_status WHERE id = 1 LIMIT 1")
    suspend fun findSyncStatus(): SyncStatusEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncStatus(status: SyncStatusEntity)
}

@Database(entities = [TransactionEntity::class, AccountEntity::class, CategoryEntity::class, CategoryMappingEntity::class, AccountMappingEntity::class, TagEntity::class, TemplateEntity::class, SyncConflictEntity::class, SyncStatusEntity::class, ScheduledOccurrenceEntity::class], version = 14, exportSchema = false)
abstract class FinexyDatabase : androidx.room.RoomDatabase() {
    abstract fun dao(): FinexyDao

    companion object {
        private val instances = mutableMapOf<String, FinexyDatabase>()

        fun get(context: Context, name: String = "finexy.db"): FinexyDatabase = synchronized(this) {
            instances.getOrPut(name) { Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .addMigrations(MIGRATION_9_10)
                .addMigrations(MIGRATION_10_11)
                .addMigrations(MIGRATION_11_12)
                .addMigrations(MIGRATION_12_13)
                .addMigrations(MIGRATION_13_14)
                .build() }
        }

        internal fun closeInstance(name: String) = synchronized(this) {
            instances.remove(name)?.close()
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_conflicts (
                        localId TEXT NOT NULL PRIMARY KEY,
                        serverId INTEGER NOT NULL,
                        localComment TEXT NOT NULL,
                        localAmountMinor INTEGER NOT NULL,
                        remoteComment TEXT NOT NULL,
                        remoteAmountMinor INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS category_mappings (localName TEXT NOT NULL PRIMARY KEY, serverId INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                database.execSQL("ALTER TABLE sync_conflicts ADD COLUMN remoteEntityJson TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sync_conflicts ADD COLUMN resolution TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS tags (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, groupId INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS transaction_templates (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, type INTEGER NOT NULL, categoryId INTEGER NOT NULL, sourceAccountId INTEGER NOT NULL, sourceAmountMinor INTEGER NOT NULL, comment TEXT NOT NULL, tagIdsJson TEXT NOT NULL DEFAULT '[]', hidden INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN syncedSnapshotJson TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE transactions ADD COLUMN pictureIdsJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE transactions ADD COLUMN geoLocationJson TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE transactions ADD COLUMN hideAmount INTEGER NOT NULL DEFAULT 0")
                // v6 mixed normal API seconds with local milliseconds. Only normalize
                // server-backed rows; don't reinterpret legitimate historical local dates.
                database.execSQL("UPDATE transactions SET time = time * 1000 WHERE serverId IS NOT NULL AND time > 0 AND time < 100000000000")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE category_mappings_new (localName TEXT NOT NULL, transactionType INTEGER NOT NULL, serverId INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(localName, transactionType))")
                // Category type 1 is income and 2 is expense in the server contract;
                // transaction types are 2 and 3. Unknown stale mappings are dropped
                // instead of being guessed across income/expense categories.
                database.execSQL("""
                    INSERT OR REPLACE INTO category_mappings_new (localName, transactionType, serverId, updatedAt)
                    SELECT m.localName, CASE c.type WHEN 1 THEN 2 WHEN 2 THEN 3 END, m.serverId, m.updatedAt
                    FROM category_mappings m
                    INNER JOIN categories c ON c.id = m.serverId
                    WHERE c.type IN (1, 2) AND c.parentId != 0 AND c.hidden = 0
                """.trimIndent())
                database.execSQL("DROP TABLE category_mappings")
                database.execSQL("ALTER TABLE category_mappings_new RENAME TO category_mappings")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_status (
                        id INTEGER NOT NULL PRIMARY KEY,
                        state TEXT NOT NULL,
                        message TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        nextRetryAt INTEGER NOT NULL,
                        lastStartedAt INTEGER NOT NULL,
                        lastFinishedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS account_mappings (localId INTEGER NOT NULL PRIMARY KEY, serverId INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                database.query("PRAGMA table_info(accounts)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) existing += cursor.getString(nameIndex)
                }
                fun add(name: String, definition: String) {
                    if (name !in existing) database.execSQL("ALTER TABLE accounts ADD COLUMN $name $definition")
                }
                add("parentId", "INTEGER NOT NULL DEFAULT 0")
                add("category", "INTEGER NOT NULL DEFAULT 1")
                add("type", "INTEGER NOT NULL DEFAULT 1")
                add("icon", "INTEGER NOT NULL DEFAULT 1")
                add("color", "TEXT NOT NULL DEFAULT '000000'")
                add("comment", "TEXT NOT NULL DEFAULT ''")
                add("displayOrder", "INTEGER NOT NULL DEFAULT 0")
                add("creditCardStatementDate", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                database.query("PRAGMA table_info(categories)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) existing += cursor.getString(nameIndex)
                }
                if ("comment" !in existing) database.execSQL("ALTER TABLE categories ADD COLUMN comment TEXT NOT NULL DEFAULT ''")
                if ("displayOrder" !in existing) database.execSQL("ALTER TABLE categories ADD COLUMN displayOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val columns = listOf(
                    "templateType" to "INTEGER NOT NULL DEFAULT 1",
                    "destinationAccountId" to "INTEGER NOT NULL DEFAULT 0",
                    "destinationAmountMinor" to "INTEGER NOT NULL DEFAULT 0",
                    "hideAmount" to "INTEGER NOT NULL DEFAULT 0",
                    "scheduledFrequencyType" to "INTEGER",
                    "scheduledFrequency" to "TEXT",
                    "scheduledStartDate" to "TEXT",
                    "scheduledEndDate" to "TEXT",
                    "utcOffset" to "INTEGER",
                    "scheduledAt" to "INTEGER",
                    "nextScheduledTime" to "INTEGER",
                    "displayOrder" to "INTEGER NOT NULL DEFAULT 0"
                )
                val existing = mutableSetOf<String>()
                database.query("PRAGMA table_info(transaction_templates)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) existing += cursor.getString(nameIndex)
                }
                columns.forEach { (name, definition) ->
                    if (name !in existing) database.execSQL("ALTER TABLE transaction_templates ADD COLUMN $name $definition")
                }
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS scheduled_occurrences (
                        templateId INTEGER NOT NULL,
                        scheduledUnixTime INTEGER NOT NULL,
                        status INTEGER NOT NULL,
                        transactionId INTEGER NOT NULL DEFAULT 0,
                        name TEXT NOT NULL DEFAULT '',
                        type INTEGER NOT NULL DEFAULT 0,
                        categoryId INTEGER NOT NULL DEFAULT 0,
                        sourceAccountId INTEGER NOT NULL DEFAULT 0,
                        destinationAccountId INTEGER NOT NULL DEFAULT 0,
                        sourceAmountMinor INTEGER NOT NULL DEFAULT 0,
                        destinationAmountMinor INTEGER NOT NULL DEFAULT 0,
                        utcOffset INTEGER NOT NULL DEFAULT 0,
                        hideAmount INTEGER NOT NULL DEFAULT 0,
                        tagIdsJson TEXT NOT NULL DEFAULT '[]',
                        comment TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(templateId, scheduledUnixTime)
                    )
                """.trimIndent())
                // Pausing blanks the frequency; keep the pre-pause values so
                // resuming can restore them instead of re-asking the user.
                val existing = mutableSetOf<String>()
                database.query("PRAGMA table_info(transaction_templates)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) existing += cursor.getString(nameIndex)
                }
                if ("pausedFromFrequencyType" !in existing) database.execSQL("ALTER TABLE transaction_templates ADD COLUMN pausedFromFrequencyType INTEGER")
                if ("pausedFromFrequency" !in existing) database.execSQL("ALTER TABLE transaction_templates ADD COLUMN pausedFromFrequency TEXT")
            }
        }
    }
}
