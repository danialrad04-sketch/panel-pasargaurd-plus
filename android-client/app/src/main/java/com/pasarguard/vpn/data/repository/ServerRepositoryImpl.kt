package com.pasarguard.vpn.data.repository

import com.pasarguard.vpn.data.db.PingHistoryDao
import com.pasarguard.vpn.data.db.PingHistoryEntity
import com.pasarguard.vpn.data.db.ServerDao
import com.pasarguard.vpn.data.db.ServerEntity
import com.pasarguard.vpn.data.db.SessionStatDao
import com.pasarguard.vpn.data.db.SessionStatEntity
import com.pasarguard.vpn.data.parser.ProxyLinkParser
import com.pasarguard.vpn.domain.model.AppSettings
import com.pasarguard.vpn.domain.model.PingHistoryPoint
import com.pasarguard.vpn.domain.model.PingProgressState
import com.pasarguard.vpn.domain.model.PingQualityMetrics
import com.pasarguard.vpn.domain.model.PingScoring
import com.pasarguard.vpn.domain.model.PingTestMode
import com.pasarguard.vpn.domain.model.SessionStatRecord
import com.pasarguard.vpn.domain.model.VpnEngineType
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.domain.repository.SettingsRepository
import com.pasarguard.vpn.util.SanitizedLogBuffer
import com.pasarguard.vpn.vpn.LatencyTester
import com.pasarguard.vpn.vpn.core.CoreSelectionResult
import com.pasarguard.vpn.vpn.core.CoreSelector
import com.pasarguard.vpn.vpn.core.SingBoxCore
import com.pasarguard.vpn.vpn.core.XrayCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val pingHistoryDao: PingHistoryDao,
    private val sessionStatDao: SessionStatDao,
    private val parser: ProxyLinkParser,
    private val settingsRepository: SettingsRepository,
    private val coreSelector: CoreSelector,
    private val xrayCore: XrayCore,
    private val singBoxCore: SingBoxCore,
    private val latencyTester: LatencyTester,
    private val logBuffer: SanitizedLogBuffer
) : ServerRepository {

    private val _pingProgress = MutableStateFlow(PingProgressState())
    override val pingProgress: StateFlow<PingProgressState> = _pingProgress.asStateFlow()

    @Volatile
    private var activeBatchJob: Job? = null

    override fun observeServers(): Flow<List<VpnServer>> =
        serverDao.observeServers().map { entities ->
            entities.mapNotNull { it.toDomain() }
        }

    override fun observePingHistory(serverId: String): Flow<List<PingHistoryPoint>> =
        pingHistoryDao.observeForServer(serverId).map { list ->
            list.reversed().map {
                PingHistoryPoint(
                    serverId = it.serverId,
                    latencyMs = it.latencyMs,
                    timestampMs = it.timestampMs
                )
            }
        }

    override fun observeSessionStats(): Flow<List<SessionStatRecord>> =
        sessionStatDao.observeAll().map { list ->
            list.map {
                SessionStatRecord(
                    id = it.id,
                    serverId = it.serverId,
                    serverName = it.serverName,
                    engineLabel = it.engineLabel,
                    startedAtMs = it.startedAtMs,
                    durationMs = it.durationMs,
                    uploadBytes = it.uploadBytes,
                    downloadBytes = it.downloadBytes,
                    dayKey = it.dayKey
                )
            }
        }

    override suspend fun getCachedServers(): List<VpnServer> = withContext(Dispatchers.IO) {
        serverDao.getAll().mapNotNull { it.toDomain() }
    }

    override suspend fun getServerById(serverId: String): VpnServer? = withContext(Dispatchers.IO) {
        serverDao.getById(serverId)?.toDomain()
    }

    override suspend fun importManualLinks(rawText: String): Int = withContext(Dispatchers.IO) {
        val parsed = parser.parseSubscriptionPayload(rawText)
        if (parsed.isEmpty()) return@withContext 0
        val existingCount = serverDao.getAll().size
        val entities = parsed.mapIndexed { idx, srv ->
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
                pingMs = null,
                isFavorite = false,
                groupName = "Manual",
                sortOrder = existingCount + idx
            )
        }
        serverDao.insertAll(entities)
        logBuffer.info("Import", "Imported ${entities.size} manual server config(s)")
        entities.size
    }

    override suspend fun deleteServer(serverId: String) = withContext(Dispatchers.IO) {
        serverDao.deleteById(serverId)
    }

    override suspend fun toggleFavorite(serverId: String) = withContext(Dispatchers.IO) {
        serverDao.toggleFavorite(serverId)
    }

    override suspend fun updatePing(serverId: String, pingMs: Int) = withContext(Dispatchers.IO) {
        serverDao.updatePing(serverId, pingMs)
    }

    override suspend fun recordSessionStat(record: SessionStatRecord) = withContext(Dispatchers.IO) {
        sessionStatDao.insert(
            SessionStatEntity(
                serverId = record.serverId,
                serverName = record.serverName,
                engineLabel = record.engineLabel,
                startedAtMs = record.startedAtMs,
                durationMs = record.durationMs,
                uploadBytes = record.uploadBytes,
                downloadBytes = record.downloadBytes,
                dayKey = record.dayKey
            )
        )
    }

    override suspend fun clearSessionStats() = withContext(Dispatchers.IO) {
        sessionStatDao.clearAll()
    }

    override suspend fun pingServer(server: VpnServer): Int = pingServerDetailed(server).avgMs

    override suspend fun pingServerDetailed(server: VpnServer): PingQualityMetrics = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSnapshot()
        measureAndPersistServer(server, settings)
    }

    override fun cancelBatchPing() {
        activeBatchJob?.cancel()
        activeBatchJob = null
        _pingProgress.value = PingProgressState(isRunning = false, completed = 0, total = 0)
    }

    override suspend fun pingAllServers(): List<VpnServer> = withContext(Dispatchers.IO) {
        val servers = getCachedServers()
        if (servers.isEmpty()) return@withContext emptyList()

        val settings = settingsRepository.getSnapshot()
        val concurrency = settings.pingMaxConcurrency.coerceIn(1, 10)
        val limiter = Semaphore(permits = concurrency)
        val completed = AtomicInteger(0)

        _pingProgress.value = PingProgressState(isRunning = true, completed = 0, total = servers.size)
        logBuffer.info("Ping", "Batch test started: ${servers.size} servers, mode=${settings.pingTestMode}, samples=${settings.pingSamplesPerServer}")

        try {
            coroutineScope {
                activeBatchJob = coroutineContext[Job]
                servers.map { srv ->
                    async {
                        limiter.withPermit {
                            val metrics = measureAndPersistServer(srv, settings)
                            val done = completed.incrementAndGet()
                            _pingProgress.value = PingProgressState(
                                isRunning = true,
                                completed = done,
                                total = servers.size
                            )
                            srv.copy(
                                pingMs = metrics.avgMs,
                                minPingMs = metrics.minMs,
                                maxPingMs = metrics.maxMs,
                                jitterMs = metrics.jitterMs,
                                packetLossPct = metrics.packetLossPct,
                                stabilityScore = metrics.stabilityScore
                            )
                        }
                    }
                }.awaitAll()
            }
        } catch (cancel: CancellationException) {
            logBuffer.info("Ping", "Batch ping cancelled at ${completed.get()}/${servers.size}")
            throw cancel
        } finally {
            activeBatchJob = null
            _pingProgress.value = PingProgressState(isRunning = false, completed = completed.get(), total = servers.size)
        }
    }

    private suspend fun measureAndPersistServer(
        server: VpnServer,
        settings: AppSettings
    ): PingQualityMetrics {
        val sampleCount = settings.pingSamplesPerServer.coerceIn(1, 5)
        val timeoutMs = settings.pingTimeoutSec.coerceIn(2, 10) * 1_000L

        val samples = when (settings.pingTestMode) {
            PingTestMode.TCP -> List(sampleCount) {
                latencyTester.measureTcpPing(server.address, server.port, timeoutMs.toInt())
            }

            PingTestMode.THROUGH_CORE -> List(sampleCount) {
                measureSingleUrlThroughCore(server, settings.pingTestUrl, settings, timeoutMs)
            }

            PingTestMode.MULTI_URL -> {
                val urls = (listOf(settings.pingTestUrl) + PingScoring.DEFAULT_TEST_URLS + settings.customPingUrls)
                    .distinct()
                    .take(sampleCount.coerceAtLeast(2).coerceAtMost(3))
                urls.map { url ->
                    measureSingleUrlThroughCore(server, url, settings, timeoutMs)
                }
            }
        }

        val metrics = PingScoring.evaluate(samples)
        serverDao.updatePingMetrics(
            id = server.id,
            avgMs = metrics.avgMs,
            minMs = metrics.minMs,
            maxMs = metrics.maxMs,
            jitterMs = metrics.jitterMs,
            packetLossPct = metrics.packetLossPct,
            stabilityScore = metrics.stabilityScore
        )
        pingHistoryDao.insert(
            PingHistoryEntity(
                serverId = server.id,
                latencyMs = metrics.avgMs,
                timestampMs = System.currentTimeMillis()
            )
        )
        pingHistoryDao.trimToLastTen(server.id)
        return metrics
    }

    private suspend fun measureSingleUrlThroughCore(
        server: VpnServer,
        url: String,
        settings: AppSettings,
        timeoutMs: Long
    ): Int = withTimeoutOrNull(timeoutMs) {
        when (val selected = coreSelector.select(server, settings.corePreference)) {
            is CoreSelectionResult.Selected -> when (selected.engine) {
                VpnEngineType.XRAY -> xrayCore.urlTest(server, url)
                VpnEngineType.SING_BOX -> singBoxCore.urlTest(server, url)
            }

            is CoreSelectionResult.Incompatible ->
                latencyTester.measureTcpPing(server.address, server.port, timeoutMs.toInt())
        }
    } ?: -1

    private fun ServerEntity.toDomain(): VpnServer? {
        val parsed = if (!rawXrayConfigJson.isNullOrBlank()) {
            parser.parseXrayJsonSubscription(rawXrayConfigJson).firstOrNull()
                ?: parser.parseSingleUri(rawUri)
        } else {
            parser.parseSingleUri(rawUri)
        } ?: return null

        return parsed.copy(
            id = id,
            name = name,
            countryCode = countryCode,
            flagEmoji = flagEmoji,
            pingMs = pingMs,
            minPingMs = minPingMs,
            maxPingMs = maxPingMs,
            jitterMs = jitterMs,
            packetLossPct = packetLossPct,
            stabilityScore = stabilityScore,
            isFavorite = isFavorite,
            groupName = groupName,
            rawXrayConfigJson = rawXrayConfigJson
        )
    }
}
