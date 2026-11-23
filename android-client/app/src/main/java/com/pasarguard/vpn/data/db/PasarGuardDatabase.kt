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
    val lastSyncedEpochMs: Long,
    val profileTitle: String? = null,
    val supportUrl: String? = null,
    val profileWebPageUrl: String? = null,
    val announce: String? = null,
    val suggestedUpdateIntervalHours: Int? = null
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
    val rawXrayConfigJson: String? = null,
    val pingMs: Int?,
    val minPingMs: Int? = null,
    val maxPingMs: Int? = null,
    val jitterMs: Int = 0,
    val packetLossPct: Int = 0,
    val stabilityScore: Int = 0,
    val isFavorite: Boolean,
    val groupName: String = "Subscription",
    val sortOrder: Int
)

@Entity(tableName = "usage_points")
data class UsagePointEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val periodStart: String,
    val bytes: Long
)

@Entity(tableName = "ping_history")
data class PingHistoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val serverId: String,
    val latencyMs: Int,
    val timestampMs: Long
)

@Entity(tableName = "subscription_profiles")
data class SubscriptionProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val subscriptionUrl: String,
    val colorHex: Long = 0xFF06B6D4L,
    val isActive: Boolean = false,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    val announce: String? = null,
    val updateIntervalHours: Int? = null,
    val lastSyncedEpochMs: Long = 0L
)

@Entity(tableName = "session_stats")
data class SessionStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serverId: String,
    val serverName: String,
    val engineLabel: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val dayKey: String
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

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(servers: List<ServerEntity>)

    @Query("UPDATE servers SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)

    @Query("UPDATE servers SET pingMs = :pingMs WHERE id = :id")
    suspend fun updatePing(id: String, pingMs: Int)

    @Query(
        """
        UPDATE servers SET
            pingMs = :avgMs,
            minPingMs = :minMs,
            maxPingMs = :maxMs,
            jitterMs = :jitterMs,
            packetLossPct = :packetLossPct,
            stabilityScore = :stabilityScore
        WHERE id = :id
        """
    )
    suspend fun updatePingMetrics(
        id: String,
        avgMs: Int,
        minMs: Int,
        maxMs: Int,
        jitterMs: Int,
        packetLossPct: Int,
        stabilityScore: Int
    )

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM servers WHERE groupName != 'Manual'")
    suspend fun clearNonManual()

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

@Dao
interface PingHistoryDao {
    @Query("SELECT * FROM ping_history WHERE serverId = :serverId ORDER BY timestampMs DESC LIMIT 10")
    fun observeForServer(serverId: String): Flow<List<PingHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PingHistoryEntity)

    @Query(
        """
        DELETE FROM ping_history WHERE serverId = :serverId AND rowId NOT IN (
            SELECT rowId FROM ping_history WHERE serverId = :serverId ORDER BY timestampMs DESC LIMIT 10
        )
        """
    )
    suspend fun trimToLastTen(serverId: String)

    @Query("DELETE FROM ping_history")
    suspend fun clearAll()
}

@Dao
interface SubscriptionProfileDao {
    @Query("SELECT * FROM subscription_profiles ORDER BY isActive DESC, name ASC")
    fun observeAll(): Flow<List<SubscriptionProfileEntity>>

    @Query("SELECT * FROM subscription_profiles")
    suspend fun getAll(): List<SubscriptionProfileEntity>

    @Query("SELECT * FROM subscription_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SubscriptionProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: SubscriptionProfileEntity)

    @Query("UPDATE subscription_profiles SET isActive = (id = :activeId)")
    suspend fun setActive(activeId: String)

    @Query("DELETE FROM subscription_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM subscription_profiles")
    suspend fun clearAll()
}

@Dao
interface SessionStatDao {
    @Query("SELECT * FROM session_stats ORDER BY startedAtMs DESC LIMIT 120")
    fun observeAll(): Flow<List<SessionStatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: SessionStatEntity)

    @Query("DELETE FROM session_stats")
    suspend fun clearAll()
}

@Entity(tableName = "payment_orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val idempotencyKey: String,
    val planId: String,
    val planTitleFa: String,
    val planTitleEn: String,
    val planCategory: String,
    val paymentMethod: String,
    val status: String,
    val basePriceToman: Long,
    val discountCode: String?,
    val discountAmountToman: Long,
    val finalPriceToman: Long,
    val destinationCardNumber: String,
    val destinationCardHolderFa: String,
    val destinationCardHolderEn: String,
    val destinationBankFa: String,
    val destinationBankEn: String,
    val payerCardLast4: String?,
    val trackingNumber: String?,
    val receiptLocalPath: String?,
    val rejectionReasonFa: String?,
    val rejectionReasonEn: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val submittedAtMs: Long?,
    val reviewedAtMs: Long?
)

@Dao
interface OrderDao {
    @Query("SELECT * FROM payment_orders ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM payment_orders WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<OrderEntity?>

    @Query("SELECT * FROM payment_orders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): OrderEntity?

    @Query("SELECT * FROM payment_orders WHERE idempotencyKey = :key LIMIT 1")
    suspend fun getByIdempotencyKey(key: String): OrderEntity?

    @Query(
        """
        SELECT * FROM payment_orders
        WHERE status IN ('AWAITING_RECEIPT', 'PENDING_REVIEW')
        ORDER BY createdAtMs DESC LIMIT 1
        """
    )
    suspend fun getLatestActiveOrder(): OrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(order: OrderEntity)
}

@Database(
    entities = [
        SubscriptionEntity::class,
        ServerEntity::class,
        UsagePointEntity::class,
        PingHistoryEntity::class,
        SubscriptionProfileEntity::class,
        SessionStatEntity::class,
        OrderEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class PasarGuardDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun serverDao(): ServerDao
    abstract fun usageDao(): UsageDao
    abstract fun pingHistoryDao(): PingHistoryDao
    abstract fun subscriptionProfileDao(): SubscriptionProfileDao
    abstract fun sessionStatDao(): SessionStatDao
    abstract fun orderDao(): OrderDao
}
