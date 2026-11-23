package com.pasarguard.vpn.data.repository

import android.content.Context
import com.pasarguard.vpn.data.api.PasarGuardApi
import com.pasarguard.vpn.data.db.ServerDao
import com.pasarguard.vpn.data.db.ServerEntity
import com.pasarguard.vpn.data.db.SubscriptionDao
import com.pasarguard.vpn.data.db.SubscriptionEntity
import com.pasarguard.vpn.data.db.SubscriptionProfileDao
import com.pasarguard.vpn.data.db.SubscriptionProfileEntity
import com.pasarguard.vpn.data.db.UsageDao
import com.pasarguard.vpn.data.db.UsagePointEntity
import com.pasarguard.vpn.data.local.SecureTokenStore
import com.pasarguard.vpn.data.parser.ProxyLinkParser
import com.pasarguard.vpn.data.parser.SubscriptionHeaderParser
import com.pasarguard.vpn.domain.model.SubscriptionCredentials
import com.pasarguard.vpn.domain.model.SubscriptionFormatStrategy
import com.pasarguard.vpn.domain.model.SubscriptionHeadersMeta
import com.pasarguard.vpn.domain.model.SubscriptionInfo
import com.pasarguard.vpn.domain.model.SubscriptionProfile
import com.pasarguard.vpn.domain.model.SubscriptionStatus
import com.pasarguard.vpn.domain.model.UsagePoint
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.domain.repository.SubscriptionRepository
import com.pasarguard.vpn.domain.usecase.ParseSubscriptionInputUseCase
import com.pasarguard.vpn.util.SanitizedLogBuffer
import com.pasarguard.vpn.worker.SubscriptionSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PasarGuardApi,
    private val parser: ProxyLinkParser,
    private val headerParser: SubscriptionHeaderParser,
    private val parseInput: ParseSubscriptionInputUseCase,
    private val tokenStore: SecureTokenStore,
    private val settingsRepository: SettingsRepository,
    private val subscriptionDao: SubscriptionDao,
    private val profileDao: SubscriptionProfileDao,
    private val serverDao: ServerDao,
    private val usageDao: UsageDao,
    private val logBuffer: SanitizedLogBuffer
) : SubscriptionRepository {

    override fun observeSubscription(): Flow<SubscriptionInfo?> =
        subscriptionDao.observeSubscription().map { it?.toDomain() }

    override fun observeUsageHistory(): Flow<List<UsagePoint>> =
        usageDao.observeUsage().map { list ->
            list.map { UsagePoint(periodStart = it.periodStart, bytes = it.bytes) }
        }

    override fun observeProfiles(): Flow<List<SubscriptionProfile>> =
        profileDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun getSavedCredentials(): SubscriptionCredentials? = tokenStore.getCredentials()

    override suspend fun validateAndSaveSubscription(
        credentials: SubscriptionCredentials
    ): Result<SubscriptionInfo> = withContext(Dispatchers.IO) {
        val result = fetchAndPersist(credentials)
        result.onSuccess { info ->
            tokenStore.saveCredentials(credentials)
            ensureProfileEntry(
                url = credentials.fullSubscriptionUrl,
                title = info.profileTitle ?: info.username,
                supportUrl = info.supportUrl,
                webPageUrl = info.profileWebPageUrl,
                announce = info.announce,
                intervalHours = info.suggestedUpdateIntervalHours
            )
        }
        result
    }

    override suspend fun addProfile(
        name: String,
        rawUrl: String,
        colorHex: Long
    ): Result<SubscriptionProfile> = withContext(Dispatchers.IO) {
        val creds = parseInput(rawUrl).getOrElse { return@withContext Result.failure(it) }
        val profile = SubscriptionProfileEntity(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { creds.token.take(10) },
            subscriptionUrl = creds.fullSubscriptionUrl,
            colorHex = colorHex,
            isActive = false
        )
        profileDao.upsert(profile)
        logBuffer.info("Profiles", "Added profile '${profile.name}'")
        Result.success(profile.toDomain())
    }

    override suspend fun switchProfile(profileId: String): Result<SubscriptionInfo> = withContext(Dispatchers.IO) {
        val profile = profileDao.getById(profileId)
            ?: return@withContext Result.failure(IllegalArgumentException("Profile not found"))
        val creds = parseInput(profile.subscriptionUrl)
            .getOrElse { return@withContext Result.failure(it) }

        val res = fetchAndPersist(creds, groupLabel = profile.name)
        res.onSuccess { info ->
            tokenStore.saveCredentials(creds)
            profileDao.setActive(profileId)
            profileDao.upsert(
                profile.copy(
                    isActive = true,
                    supportUrl = info.supportUrl ?: profile.supportUrl,
                    webPageUrl = info.profileWebPageUrl ?: profile.webPageUrl,
                    announce = info.announce ?: profile.announce,
                    updateIntervalHours = info.suggestedUpdateIntervalHours ?: profile.updateIntervalHours,
                    lastSyncedEpochMs = System.currentTimeMillis()
                )
            )
            logBuffer.info("Profiles", "Switched active profile to '${profile.name}'")
        }
        res
    }

    override suspend fun deleteProfile(profileId: String) = withContext(Dispatchers.IO) {
        profileDao.deleteById(profileId)
        logBuffer.info("Profiles", "Removed profile")
    }

    override suspend fun syncSubscription(): Result<SubscriptionInfo> = withContext(Dispatchers.IO) {
        val credentials = tokenStore.getCredentials()
            ?: return@withContext Result.failure(IllegalStateException("NOT_LOGGED_IN"))
        val activeProfile = profileDao.getAll().firstOrNull { it.isActive }
        val result = fetchAndPersist(credentials, groupLabel = activeProfile?.name ?: "Subscription")
        result.onSuccess { info ->
            if (activeProfile != null) {
                profileDao.upsert(
                    activeProfile.copy(
                        supportUrl = info.supportUrl ?: activeProfile.supportUrl,
                        webPageUrl = info.profileWebPageUrl ?: activeProfile.webPageUrl,
                        announce = info.announce ?: activeProfile.announce,
                        updateIntervalHours = info.suggestedUpdateIntervalHours ?: activeProfile.updateIntervalHours,
                        lastSyncedEpochMs = System.currentTimeMillis()
                    )
                )
            } else {
                ensureProfileEntry(
                    url = credentials.fullSubscriptionUrl,
                    title = info.profileTitle ?: info.username,
                    supportUrl = info.supportUrl,
                    webPageUrl = info.profileWebPageUrl,
                    announce = info.announce,
                    intervalHours = info.suggestedUpdateIntervalHours
                )
            }
        }
        result
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        tokenStore.clear()
        subscriptionDao.clear()
        profileDao.clearAll()
        serverDao.clearAll()
        usageDao.clearAll()
    }

    private suspend fun ensureProfileEntry(
        url: String,
        title: String,
        supportUrl: String?,
        webPageUrl: String?,
        announce: String?,
        intervalHours: Int?
    ) {
        val existing = profileDao.getAll().firstOrNull { it.subscriptionUrl == url }
        val id = existing?.id ?: UUID.randomUUID().toString()
        profileDao.upsert(
            SubscriptionProfileEntity(
                id = id,
                name = existing?.name ?: title,
                subscriptionUrl = url,
                colorHex = existing?.colorHex ?: 0xFF06B6D4L,
                isActive = true,
                supportUrl = supportUrl,
                webPageUrl = webPageUrl,
                announce = announce,
                updateIntervalHours = intervalHours,
                lastSyncedEpochMs = System.currentTimeMillis()
            )
        )
        profileDao.setActive(id)
    }

    private suspend fun fetchAndPersist(
        credentials: SubscriptionCredentials,
        groupLabel: String = "Subscription"
    ): Result<SubscriptionInfo> = runCatching {
        val baseSubUrl = credentials.fullSubscriptionUrl
        val nowMs = System.currentTimeMillis()
        val settings = settingsRepository.getSnapshot()
        val strategy = settings.subscriptionFormatStrategy

        val collectedHeaders = LinkedHashMap<String, String>()

        val infoResponse = runCatching { api.getSubscriptionInfo("$baseSubUrl/info") }.getOrNull()
        val infoDto = infoResponse?.takeIf { it.isSuccessful }?.body()
        infoResponse?.headers()?.let { hdrs ->
            for (name in hdrs.names()) {
                hdrs[name]?.let { collectedHeaders[name] = it }
            }
        }

        var parsedServers: List<VpnServer> = emptyList()
        var headerUserInfo: com.pasarguard.vpn.data.parser.SubscriptionHeaderUserInfo? = null

        if (strategy == SubscriptionFormatStrategy.PREFER_XRAY_JSON) {
            for (suffix in listOf("xray", "v2ray-json")) {
                val resp = runCatching { api.getClientSubscription("$baseSubUrl/$suffix") }.getOrNull()
                if (resp != null && resp.isSuccessful) {
                    for (name in resp.headers().names()) {
                        resp.headers()[name]?.let { collectedHeaders[name] = it }
                    }
                    val bodyStr = resp.body()?.string().orEmpty()
                    val jsonServers = parser.parseXrayJsonSubscription(bodyStr)
                    if (jsonServers.isNotEmpty()) {
                        parsedServers = jsonServers
                        headerUserInfo = parser.parseSubscriptionUserInfoHeader(
                            resp.headers()["subscription-userinfo"]
                        )
                        logBuffer.info("Sync", "Loaded ${jsonServers.size} servers from panel Xray-JSON ($suffix)")
                        break
                    }
                }
            }
        }

        if (parsedServers.isEmpty()) {
            val rawResp = runCatching { api.getRawLinks("$baseSubUrl/raw") }.getOrNull()
            if (rawResp != null && rawResp.isSuccessful) {
                for (name in rawResp.headers().names()) {
                    rawResp.headers()[name]?.let { collectedHeaders[name] = it }
                }
                val links = rawResp.body()?.links.orEmpty()
                parsedServers = parser.parseLinks(links)
                if (parsedServers.isNotEmpty()) {
                    logBuffer.info("Sync", "Loaded ${parsedServers.size} servers from /raw links")
                }
            }
        }

        if (parsedServers.isEmpty()) {
            val base64Resp = api.getBase64Subscription(baseSubUrl)
            if (!base64Resp.isSuccessful && infoDto == null) {
                throw IOException("HTTP ${base64Resp.code()}: Unable to fetch PasarGuard subscription")
            }
            for (name in base64Resp.headers().names()) {
                base64Resp.headers()[name]?.let { collectedHeaders[name] = it }
            }
            headerUserInfo = headerUserInfo ?: parser.parseSubscriptionUserInfoHeader(
                base64Resp.headers()["subscription-userinfo"]
            )
            val rawBody = base64Resp.body()?.string().orEmpty()
            parsedServers = parser.parseSubscriptionPayload(rawBody)
            logBuffer.info("Sync", "Loaded ${parsedServers.size} servers from Base64 subscription")
        }

        if (infoDto == null && parsedServers.isEmpty() && headerUserInfo == null) {
            throw IOException("Subscription returned no metadata or proxy links")
        }

        val headersMeta: SubscriptionHeadersMeta = headerParser.parseMetadata(collectedHeaders)
        headersMeta.updateIntervalHours?.let { suggestedHours ->
            if (settings.autoRefreshIntervalHours > 0) {
                SubscriptionSyncWorker.schedule(context, suggestedHours.toLong())
            }
        }

        val entity = SubscriptionEntity(
            singletonId = 1,
            username = infoDto?.username?.ifBlank { credentials.token.take(12) }
                ?: headersMeta.profileTitle
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
            lastSyncedEpochMs = nowMs,
            profileTitle = headersMeta.profileTitle,
            supportUrl = headersMeta.supportUrl,
            profileWebPageUrl = headersMeta.profileWebPageUrl,
            announce = headersMeta.announce,
            suggestedUpdateIntervalHours = headersMeta.updateIntervalHours
        )
        subscriptionDao.upsert(entity)

        // Preserve Manual-imported servers + existing favorites & latency metrics
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
                    rawXrayConfigJson = srv.rawXrayConfigJson,
                    pingMs = prev?.pingMs,
                    minPingMs = prev?.minPingMs,
                    maxPingMs = prev?.maxPingMs,
                    jitterMs = prev?.jitterMs ?: 0,
                    packetLossPct = prev?.packetLossPct ?: 0,
                    stabilityScore = prev?.stabilityScore ?: 0,
                    isFavorite = prev?.isFavorite ?: false,
                    groupName = groupLabel,
                    sortOrder = index
                )
            }
            serverDao.clearNonManual()
            serverDao.insertAll(merged)
        }

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
        lastSyncedEpochMs = lastSyncedEpochMs,
        profileTitle = profileTitle,
        supportUrl = supportUrl,
        profileWebPageUrl = profileWebPageUrl,
        announce = announce,
        suggestedUpdateIntervalHours = suggestedUpdateIntervalHours
    )

    private fun SubscriptionProfileEntity.toDomain() = SubscriptionProfile(
        id = id,
        name = name,
        subscriptionUrl = subscriptionUrl,
        colorHex = colorHex,
        isActive = isActive,
        supportUrl = supportUrl,
        webPageUrl = webPageUrl,
        announce = announce,
        updateIntervalHours = updateIntervalHours,
        lastSyncedEpochMs = lastSyncedEpochMs
    )
}
