package com.pasarguard.vpn.data.repository

import com.pasarguard.vpn.data.db.ServerDao
import com.pasarguard.vpn.data.db.ServerEntity
import com.pasarguard.vpn.data.parser.ProxyLinkParser
import com.pasarguard.vpn.domain.model.VpnServer
import com.pasarguard.vpn.domain.repository.ServerRepository
import com.pasarguard.vpn.vpn.LatencyTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val parser: ProxyLinkParser,
    private val latencyTester: LatencyTester
) : ServerRepository {

    override fun observeServers(): Flow<List<VpnServer>> =
        serverDao.observeServers().map { entities ->
            entities.mapNotNull { it.toDomain() }
        }

    override suspend fun getCachedServers(): List<VpnServer> = withContext(Dispatchers.IO) {
        serverDao.getAll().mapNotNull { it.toDomain() }
    }

    override suspend fun toggleFavorite(serverId: String) = withContext(Dispatchers.IO) {
        serverDao.toggleFavorite(serverId)
    }

    override suspend fun updatePing(serverId: String, pingMs: Int) = withContext(Dispatchers.IO) {
        serverDao.updatePing(serverId, pingMs)
    }

    override suspend fun pingServer(server: VpnServer): Int = withContext(Dispatchers.IO) {
        val ms = latencyTester.measureTcpPing(server.address, server.port)
        serverDao.updatePing(server.id, ms)
        ms
    }

    override suspend fun pingAllServers(): List<VpnServer> = withContext(Dispatchers.IO) {
        val servers = getCachedServers()
        val limiter = Semaphore(permits = 6)
        coroutineScope {
            servers.map { srv ->
                async {
                    limiter.withPermit {
                        val ms = latencyTester.measureTcpPing(srv.address, srv.port)
                        serverDao.updatePing(srv.id, ms)
                        srv.copy(pingMs = ms)
                    }
                }
            }.awaitAll()
        }
    }

    private fun ServerEntity.toDomain(): VpnServer? {
        val parsed = parser.parseSingleUri(rawUri) ?: return null
        return parsed.copy(
            id = id,
            name = name,
            countryCode = countryCode,
            flagEmoji = flagEmoji,
            pingMs = pingMs,
            isFavorite = isFavorite
        )
    }
}
