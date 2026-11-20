package com.pasarguard.vpn.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "subscription")
data class SubscriptionEntity(
    @PrimaryKey val singletonId: Int = 1,
    val username: String,
    val status: String,
    val usedTrafficBytes: Long,
    val lifetimeUsedTrafficBytes: Long,
    val dataLimitBytes: Long?,
    val resetStrategy: String,
    val expireEpochSeconds: Long?,
    val onHoldDurationSeconds: Long?,
    val hwidLimit: Int?,
    val subscriptionUrl: String,
    val subUpdatedAt: String?,
    val lastSyncedEpochMs: Long
)

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val countryCode: String,
    val flagEmoji: String,
    val rawUri: String,
    val pingMs: Int?,
    val isFavorite: Boolean,
    val sortOrder: Int
)

@Entity(tableName = "usage_points")
data class UsagePointEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val periodStart: String,
    val bytes: Long
)

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscription WHERE singletonId = 1 LIMIT 1")
    fun observeSubscription(): Flow<SubscriptionEntity?>

    @Query("SELECT * FROM subscription WHERE singletonId = 1 LIMIT 1")
    suspend fun getSubscription(): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubscriptionEntity)

    @Query("DELETE FROM subscription")
    suspend fun clear()
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY isFavorite DESC, sortOrder ASC")
    fun observeServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY isFavorite DESC, sortOrder ASC")
    suspend fun getAll(): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(servers: List<ServerEntity>)

    @Query("UPDATE servers SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)

    @Query("UPDATE servers SET pingMs = :pingMs WHERE id = :id")
    suspend fun updatePing(id: String, pingMs: Int)

    @Query("DELETE FROM servers")
    suspend fun clearAll()
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_points ORDER BY rowId ASC")
    fun observeUsage(): Flow<List<UsagePointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<UsagePointEntity>)

    @Query("DELETE FROM usage_points")
    suspend fun clearAll()
}

@Database(
    entities = [SubscriptionEntity::class, ServerEntity::class, UsagePointEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PasarGuardDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun serverDao(): ServerDao
    abstract fun usageDao(): UsageDao
}
