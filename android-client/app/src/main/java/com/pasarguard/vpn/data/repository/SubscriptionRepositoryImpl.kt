package com.pasarguard.vpn.data.repository

import com.pasarguard.vpn.data.api.PasarGuardApi
import com.pasarguard.vpn.data.db.ServerDao
import com.pasarguard.vpn.data.db.ServerEntity
import com.pasarguard.vpn.data.db.SubscriptionDao
import com.pasarguard.vpn.data.db.SubscriptionEntity
import com.pasarguard.vpn.data.db.UsageDao
import com.pasarguard.vpn.data.db.UsagePointEntity
import com.pasarguard.vpn.data.local.SecureTokenStore
import com.pasarguard.vpn.data.parser.ProxyLinkParser
import com.pasarguard.vpn.domain.model.SubscriptionCredentials
import com.pasarguard.vpn.domain.model.SubscriptionInfo
import com.pasarguard.vpn.domain.model.SubscriptionStatus
import com.pasarguard.vpn.domain.model.UsagePoint
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepositoryImpl @Inject constructor(
    private val api: PasarGuardApi,
    private val parser: ProxyLinkParser,
    private val tokenStore: SecureTokenStore,
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val usageDao: UsageDao
) : SubscriptionRepository {

    override fun observeSubscription(): Flow<SubscriptionInfo?> =
        subscriptionDao.observeSubscription().map { it?.toDomain() }

    override fun observeUsageHistory(): Flow<List<UsagePoint>> =
        usageDao.observeUsage().map { list ->
            list.map { UsagePoint(periodStart = it.periodStart, bytes = it.bytes) }
        }

    override fun getSavedCredentials(): SubscriptionCredentials? = tokenStore.getCredentials()

    override suspend fun validateAndSaveSubscription(
        credentials: SubscriptionCredentials
    ): Result<SubscriptionInfo> = withContext(Dispatchers.IO) {
        val result = fetchAndPersist(credentials)
        if (result.isSuccess) {
            tokenStore.saveCredentials(credentials)
        }
        result
    }

    override suspend fun syncSubscription(): Result<SubscriptionInfo> = withContext(Dispatchers.IO) {
        val credentials = tokenStore.getCredentials()
            ?: return@withContext Result.failure(IllegalStateException("NOT_LOGGED_IN"))
        fetchAndPersist(credentials)
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        tokenStore.clear()
        subscriptionDao.clear()
        serverDao.clearAll()
        usageDao.clearAll()
    }

    /**
     * Fetches subscription metadata from `/sub/{token}/info`, proxy server configs from
     * `/sub/{token}/raw` (with automatic fallback to Base64 `/sub/{token}` + `subscription-userinfo`
     * headers), and historical traffic from `/sub/{token}/usage`.
     */
    private suspend fun fetchAndPersist(
        credentials: SubscriptionCredentials
    ): Result<SubscriptionInfo> = runCatching {
        val baseSubUrl = credentials.fullSubscriptionUrl
        val nowMs = System.currentTimeMillis()

        // 1. Fetch primary subscription info from PasarGuard `/sub/{token}/info`
        val infoResponse = runCatching { api.getSubscriptionInfo("$baseSubUrl/info") }.getOrNull()
        val infoDto = infoResponse?.takeIf { it.isSuccessful }?.body()

        // 2. Fetch server configs: try `/sub/{token}/raw` JSON first, fall back to Base64 `/sub/{token}`
        var parsedServers: List<VpnServer> = emptyList()
        var headerUserInfo = null as com.pasarguard.vpn.data.parser.SubscriptionHeaderUserInfo?

        val rawResp = runCatching { api.getRawLinks("$baseSubUrl/raw") }.getOrNull()
        if (rawResp != null && rawResp.isSuccessful) {
            val links = rawResp.body()?.links.orEmpty()
            parsedServers = parser.parseLinks(links)
        }

        if (parsedServers.isEmpty()) {
            val base64Resp = api.getBase64Subscription(baseSubUrl)
            if (!base64Resp.isSuccessful && infoDto == null) {
                throw IOException("HTTP ${base64Resp.code()}: Unable to fetch PasarGuard subscription")
            }
            headerUserInfo = parser.parseSubscriptionUserInfoHeader(
                base64Resp.headers()["subscription-userinfo"]
            )
            val rawBody = base64Resp.body()?.string().orEmpty()
            parsedServers = parser.parseSubscriptionPayload(rawBody)
        }

        if (infoDto == null && parsedServers.isEmpty() && headerUserInfo == null) {
            throw IOException("Subscription returned no metadata or proxy links")
        }

        // 3. Persist normalized subscription snapshot
        val entity = SubscriptionEntity(
            singletonId = 1,
            username = infoDto?.username?.ifBlank { credentials.token.take(12) }
                ?: credentials.token.take(12),
            status = infoDto?.status ?: "active",
            usedTrafficBytes = infoDto?.usedTraffic
                ?: ((headerUserInfo?.upload ?: 0L) + (headerUserInfo?.download ?: 0L)),
            lifetimeUsedTrafficBytes = infoDto?.lifetimeUsedTraffic
                ?: infoDto?.usedTraffic
                ?: ((headerUserInfo?.upload ?: 0L) + (headerUserInfo?.download ?: 0L)),
            dataLimitBytes = infoDto?.dataLimit ?: headerUserInfo?.total,
            resetStrategy = infoDto?.dataLimitResetStrategy ?: "no_reset",
            expireEpochSeconds = infoDto?.expire ?: headerUserInfo?.expire,
            onHoldDurationSeconds = infoDto?.onHoldExpireDuration,
            hwidLimit = infoDto?.hwidLimit,
            subscriptionUrl = credentials.fullSubscriptionUrl,
            subUpdatedAt = infoDto?.subUpdatedAt,
            lastSyncedEpochMs = nowMs
        )
        subscriptionDao.upsert(entity)

        // 4. Persist servers while preserving existing favorite stars and cached latency
        if (parsedServers.isNotEmpty()) {
            val existingById = serverDao.getAll().associateBy { it.id }
            val merged = parsedServers.mapIndexed { index, srv ->
                val prev = existingById[srv.id]
                ServerEntity(
                    id = srv.id,
                    name = srv.name,
                    protocol = srv.protocol.name,
                    address = srv.address,
                    port = srv.port,
                    countryCode = srv.countryCode,
                    flagEmoji = srv.flagEmoji,
                    rawUri = srv.rawUri,
                    pingMs = prev?.pingMs,
                    isFavorite = prev?.isFavorite ?: false,
                    sortOrder = index
                )
            }
            serverDao.clearAll()
            serverDao.insertAll(merged)
        }

        // 5. Best-effort usage chart sync from `/sub/{token}/usage`
        runCatching {
            val usageResp = api.getSubscriptionUsage("$baseSubUrl/usage")
            if (usageResp.isSuccessful) {
                val buckets = usageResp.body()?.stats?.values?.flatten().orEmpty()
                usageDao.clearAll()
                if (buckets.isNotEmpty()) {
                    usageDao.insertAll(
                        buckets.map {
                            UsagePointEntity(periodStart = it.periodStart, bytes = it.totalTraffic)
                        }
                    )
                }
            }
        }

        entity.toDomain()
    }

    private fun SubscriptionEntity.toDomain() = SubscriptionInfo(
        username = username,
        status = SubscriptionStatus.fromRaw(status),
        usedTrafficBytes = usedTrafficBytes,
        lifetimeUsedTrafficBytes = lifetimeUsedTrafficBytes,
        dataLimitBytes = dataLimitBytes,
        resetStrategy = resetStrategy,
        expireEpochSeconds = expireEpochSeconds,
        onHoldDurationSeconds = onHoldDurationSeconds,
        hwidLimit = hwidLimit,
        subscriptionUrl = subscriptionUrl,
        subUpdatedAt = subUpdatedAt,
        lastSyncedEpochMs = lastSyncedEpochMs
    )
}
