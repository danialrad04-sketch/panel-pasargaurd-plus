import crypto from 'node:crypto'
import { Router } from 'express'
import {
  doctorDnsServices,
  forkServicesData,
  getAllInboundsFromCores,
  nextId,
  store,
  type StoredDNSAccount,
  type StoredDNSPolicy,
  type StoredSmartDNSRoute,
  type StoredTunnel,
} from '../store'
import { requireAuth, type AuthedRequest } from './auth'

export const plusRouter = Router()

function formatTunnelNode(n: (typeof store.nodes)[number]) {
  const core = store.cores.find(c => c.id === n.core_config_id)
  const inbounds = (core?.config as { inbounds?: Array<{ tag?: string }> } | undefined)?.inbounds ?? []
  return {
    id: n.id,
    name: n.name,
    address: n.address,
    status: n.status,
    core_type: core?.type || 'xray',
    inbound_tags: inbounds.map(i => String(i.tag || '')).filter(Boolean),
  }
}

function buildCatalog() {
  const raw = (doctorDnsServices as { services?: Record<string, unknown> | Array<Record<string, unknown>> }).services
  const services: Array<{
    key: string
    name: string
    label: string
    groups: Array<{ key: string; name: string; label: string; domains: string[] }>
  }> = []

  if (Array.isArray(raw)) {
    for (const s of raw) {
      const key = String(s.key || s.id || '')
      const label = String(s.label || s.name || key)
      const groups = Array.isArray(s.groups)
        ? s.groups.map((g: Record<string, unknown>) => ({
            key: String(g.key || ''),
            name: String(g.name || g.label || g.key || ''),
            label: String(g.label || g.name || g.key || ''),
            domains: Array.isArray(g.domains) ? g.domains.map(String) : [],
          }))
        : []
      if (key) services.push({ key, name: label, label, groups })
    }
  } else if (raw && typeof raw === 'object') {
    for (const [key, val] of Object.entries(raw)) {
      const obj = (val || {}) as Record<string, unknown>
      const label = String(obj.label || obj.name || key)
      const groupsRaw = obj.groups
      const groups: Array<{ key: string; name: string; label: string; domains: string[] }> = []
      if (Array.isArray(groupsRaw)) {
        for (const g of groupsRaw as Array<Record<string, unknown>>) {
          const gKey = String(g.key || g.name || '')
          groups.push({
            key: gKey,
            name: String(g.name || g.label || gKey),
            label: String(g.label || g.name || gKey),
            domains: Array.isArray(g.domains) ? g.domains.map(String) : [],
          })
        }
      } else if (groupsRaw && typeof groupsRaw === 'object') {
        for (const [gKey, gVal] of Object.entries(groupsRaw as Record<string, unknown>)) {
          const gObj = (gVal || {}) as Record<string, unknown>
          groups.push({
            key: gKey,
            name: String(gObj.name || gObj.label || gKey),
            label: String(gObj.label || gObj.name || gKey),
            domains: Array.isArray(gObj.domains) ? gObj.domains.map(String) : Array.isArray(gVal) ? gVal.map(String) : [],
          })
        }
      }
      services.push({ key, name: label, label, groups })
    }
  }

  let groupCount = 0
  let domainCount = 0
  for (const s of services) {
    groupCount += s.groups.length
    for (const g of s.groups) domainCount += g.domains.length
  }

  const presets = [
    { key: 'console_gaming', label: ['کنسول و بازی', 'Console & Gaming'], services: ['playstation', 'xbox', 'steam', 'epic', 'riot'] },
    { key: 'ai_work', label: ['هوش مصنوعی و کار', 'AI & Developer'], services: ['openai', 'github', 'google', 'docker'] },
    { key: 'streaming', label: ['استریم و رسانه', 'Streaming'], services: ['youtube', 'netflix', 'spotify', 'twitch'] },
  ]

  return {
    services,
    presets,
    counts: { services: services.length, groups: groupCount, domains: domainCount },
    fork_services: forkServicesData,
  }
}

// ---------- Native Operations ----------
plusRouter.get('/api/operations/readiness', requireAuth, (_req, res) => {
  const connected = store.nodes.filter(n => n.status === 'connected').length
  res.json({
    critical: 0,
    warnings: 0,
    checks: [
      { code: 'disk', status: 'ok', detail: '82 GB free on primary volume (68% free)', free_percent: 68 },
      { code: 'node_certificate', status: 'ok', detail: `${connected}/${store.nodes.length} node mTLS certificates valid`, days_remaining: 342 },
      { code: 'panel_certificate', status: 'ok', detail: 'Panel TLS certificate valid', days_remaining: 86 },
      { code: 'backup', status: 'ok', detail: 'Configuration state healthy' },
      { code: 'carrier', status: 'ok', detail: `${store.tunnels.filter(t => t.enabled).length} encrypted carriers configured` },
      { code: 'failover', status: 'ok', detail: `${store.tunnelFailovers.length} failover policy active` },
      { code: 'smartdns', status: 'ok', detail: `${store.smartDnsRoutes.length} SmartDNS gateway routes registered` },
    ],
  })
})

plusRouter.get('/api/operations/runtime-recovery', requireAuth, (_req, res) => {
  res.json({
    fingerprint: `pg_plus_${store.tunnels.length}_${store.nodes.length}`,
    nodes: store.nodes.map(n => ({
      node_id: n.id,
      name: n.name,
      available: n.status === 'connected',
      required: store.tunnels.some(t => t.source_node_id === n.id || t.exit_node_id === n.id),
      paqet_links: store.tunnels
        .filter(t => t.source_node_id === n.id || t.exit_node_id === n.id)
        .map(t => ({
          id: `tunnel-${t.id}`,
          revision: t.revision,
          orphan: false,
          enabled: t.enabled,
          engine: t.engine_id,
        })),
    })),
  })
})

plusRouter.post('/api/operations/runtime-recovery', requireAuth, (_req, res) => {
  res.json({ ok: true, recovered: 0, cleaned_orphans: 0 })
})

// ---------- Native Tunnels ----------
plusRouter.get('/api/tunnels', requireAuth, (_req, res) => {
  res.json({
    items: store.tunnels,
    nodes: store.nodes.map(formatTunnelNode),
    applying: false,
    lease_until: null,
    last_result: {
      ok: true,
      state: 'applied',
      applied_at: new Date().toISOString(),
      nodes: store.nodes.map(n => ({ node_id: n.id, ok: n.status === 'connected' })),
    },
    traffic_readiness: 'not_probed',
  })
})

plusRouter.post('/api/tunnels', requireAuth, (req: AuthedRequest, res) => {
  const b = req.body || {}
  const tunnel: StoredTunnel = {
    id: nextId(store.tunnels),
    name: String(b.name || `Tunnel ${store.tunnels.length + 1}`),
    source_node_id: Number(b.source_node_id),
    exit_node_id: Number(b.exit_node_id),
    inbound_tags: Array.isArray(b.inbound_tags) ? b.inbound_tags : getAllInboundsFromCores().slice(0, 1).map(i => i.tag),
    domains: Array.isArray(b.domains) ? b.domains : [],
    port: Number(b.port || 24430 + store.tunnels.length),
    enabled: b.enabled !== undefined ? Boolean(b.enabled) : true,
    health_enabled: b.health_enabled !== undefined ? Boolean(b.health_enabled) : true,
    transport: b.transport || 'paqet',
    paqet_options: b.paqet_options || {},
    carrier_options: b.carrier_options || {},
    engine_id: b.transport || 'paqet',
    revision: 1,
    applied_revision: 0,
    networks: Array.isArray(b.networks) ? b.networks : ['tcp', 'udp'],
  }
  store.tunnels.push(tunnel)
  store.tunnelAuditLogs.unshift({
    id: nextId(store.tunnelAuditLogs),
    created_at: new Date().toISOString(),
    actor: req.admin?.username || 'admin',
    action: 'create_tunnel',
    target_id: String(tunnel.id),
    details: { name: tunnel.name, transport: tunnel.transport },
  })
  res.status(201).json(tunnel)
})

plusRouter.get('/api/tunnels/audit', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 20)
  res.json(store.tunnelAuditLogs.slice(offset, offset + limit))
})

plusRouter.post('/api/tunnels/wizard/plan', requireAuth, (req, res) => {
  const b = req.body || {}
  const sourceId = Number(b.source_node_id || 2)
  const exitId = Number(b.exit_node_id || 1)
  res.json({
    ok: true,
    blockers: [],
    warnings: [],
    candidates: [
      { id: 'paqet', label: 'Paqet · KCP', compatible: true, reason: null },
      { id: 'backhaul', label: 'Backhaul · TCPMux', compatible: true, reason: null },
      { id: 'gost', label: 'GOST · Relay', compatible: true, reason: null },
      { id: 'shadowsocks-2022', label: 'Shadowsocks 2022', compatible: true, reason: null },
    ],
    max_deployments: b.mode === 'thorough' ? 4 : 2,
    planned_port: Number(b.port || 24500),
    source_node_id: sourceId,
    exit_node_id: exitId,
  })
})

plusRouter.post('/api/tunnels/wizard', requireAuth, (req: AuthedRequest, res) => {
  const b = req.body || {}
  const sourceNode = store.nodes.find(n => n.id === Number(b.source_node_id || 2))
  const exitNode = store.nodes.find(n => n.id === Number(b.exit_node_id || 1))
  const tunnel: StoredTunnel = {
    id: nextId(store.tunnels),
    name: String(b.name || `Wizard Tunnel ${store.tunnels.length + 1}`),
    source_node_id: Number(b.source_node_id || 2),
    exit_node_id: Number(b.exit_node_id || 1),
    inbound_tags: Array.isArray(b.inbound_tags) ? b.inbound_tags : ['VLESS_REALITY'],
    domains: Array.isArray(b.domains) ? b.domains : [],
    port: Number(b.port || 24500 + store.tunnels.length),
    enabled: true,
    health_enabled: true,
    transport: b.transport || 'paqet',
    paqet_options: b.paqet_options || { kcp: { mode: 'fast' }, mtu: 1350 },
    carrier_options: b.carrier_options || {},
    engine_id: b.transport || 'paqet',
    revision: 1,
    applied_revision: 1,
    networks: ['tcp', 'udp'],
  }
  store.tunnels.push(tunnel)
  const jobId = `job_${crypto.randomBytes(4).toString('hex')}`
  const job = {
    id: jobId,
    kind: 'quick_tunnel',
    target: `${sourceNode?.name || 'Source'} -> ${exitNode?.name || 'Exit'}`,
    state: 'ready',
    progress: 100,
    node_id: tunnel.source_node_id,
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
    result: {
      steps: [
        {
          id: 'paqet',
          label: 'Paqet · KCP',
          state: 'healthy',
          reason: null,
          metrics: { tcp: true, udp: true, tcp_rtt_ms: 28, udp_rtt_ms: 33, udp_loss_percent: 0, sample_mbps: 192.5 },
          score: 96,
        },
        {
          id: 'backhaul',
          label: 'Backhaul · TCPMux',
          state: 'healthy',
          reason: null,
          metrics: { tcp: true, udp: false, tcp_rtt_ms: 34, udp_rtt_ms: 0, udp_loss_percent: 0, sample_mbps: 164.0 },
          score: 84,
        },
      ],
      winner: { id: 'paqet', label: 'Paqet · KCP', tunnel_id: tunnel.id, score: 96 },
    },
    actor: req.admin?.username || 'admin',
  }
  store.provisionJobs.unshift(job)
  res.json(job)
})

plusRouter.post('/api/tunnels/apply', requireAuth, (req: AuthedRequest, res) => {
  for (const t of store.tunnels) {
    t.applied_revision = t.revision
  }
  store.tunnelAuditLogs.unshift({
    id: nextId(store.tunnelAuditLogs),
    created_at: new Date().toISOString(),
    actor: req.admin?.username || 'admin',
    action: 'apply_tunnels',
    target_id: 'all',
    details: { ok: true, count: store.tunnels.length },
  })
  res.json({ ok: true, applied: store.tunnels.length, results: store.tunnels.map(t => ({ tunnel_id: t.id, ok: true })) })
})

plusRouter.get('/api/tunnels/paqet/schema', requireAuth, (_req, res) => {
  res.json({
    engine: 'paqet-alpha.21',
    blocks: ['transport', 'kcp', 'network', 'socks5'],
    modes: ['client', 'server'],
    kcp_modes: ['normal', 'fast', 'fast2', 'fast3', 'manual'],
    defaults: { conn: 4, kcp: { mode: 'fast', mtu: 1350, nodelay: 1, interval: 10, resend: 2, nocongestion: 1 } },
    schema: {},
  })
})

plusRouter.get('/api/tunnels/nodes/:nodeId/paqet', requireAuth, (req, res) => {
  const node = store.nodes.find(n => n.id === Number(req.params.nodeId))
  res.json({
    node_id: Number(req.params.nodeId),
    installed: true,
    version: '0.4.2',
    interfaces: [{ name: 'eth0', address: node?.address || '0.0.0.0', gateway: '10.0.0.1', mac: '02:42:ac:11:00:02' }],
  })
})

plusRouter.post('/api/tunnels/nodes/:nodeId/paqet', requireAuth, (req, res) => {
  res.json({ ok: true, node_id: Number(req.params.nodeId), interfaces: [{ name: 'eth0', router_mac: '02:42:ac:11:00:01' }] })
})

plusRouter.get('/api/tunnels/health/state', requireAuth, (_req, res) => {
  res.json(
    store.tunnels.map(t => ({
      tunnel_id: t.id,
      revision: t.revision,
      checked_at: new Date().toISOString(),
      result: {
        healthy: t.enabled,
        status: t.enabled ? 'healthy' : 'disabled',
        tcp_rtt_ms: 28 + (t.id % 5) * 6,
        udp_rtt_ms: 34 + (t.id % 5) * 7,
        udp_loss_percent: 0.0,
        sample_mbps: 185.4,
      },
    }))
  )
})

plusRouter.get('/api/tunnels/failover/state', requireAuth, (_req, res) => {
  res.json(
    store.tunnelFailovers.map((p, idx) => ({
      source_node_id: p.source_node_id,
      candidates: [p.primary_tunnel_id, ...((p.backup_tunnel_ids as number[]) || [])].filter(Boolean),
      revision: idx + 1,
      enabled: true,
      suspended: false,
      failure_threshold: p.failure_threshold ?? 3,
      cooldown_seconds: 60,
      last_result: { state: 'healthy', active_tunnel_id: p.active_tunnel_id },
    }))
  )
})

plusRouter.put('/api/tunnels/failover/:sourceId', requireAuth, (req, res) => {
  const sourceId = Number(req.params.sourceId)
  const b = req.body || {}
  const candidates = Array.isArray(b.candidates) ? b.candidates.map(Number) : []
  const existing = store.tunnelFailovers.find(p => Number(p.source_node_id) === sourceId)
  const next = {
    source_node_id: sourceId,
    primary_tunnel_id: candidates[0] ?? b.primary_tunnel_id ?? null,
    backup_tunnel_ids: candidates.slice(1),
    auto_switch: true,
    probe_interval_sec: 15,
    failure_threshold: Number(b.failure_threshold || 3),
    active_tunnel_id: candidates[0] ?? null,
  }
  if (existing) Object.assign(existing, next)
  else store.tunnelFailovers.push(next)
  res.json(next)
})

plusRouter.delete('/api/tunnels/failover/:sourceId', requireAuth, (req, res) => {
  const sourceId = Number(req.params.sourceId)
  store.tunnelFailovers = store.tunnelFailovers.filter(p => Number(p.source_node_id) !== sourceId)
  res.json({ detail: 'Failover policy deleted' })
})

plusRouter.get('/api/tunnels/engines', requireAuth, (_req, res) => {
  res.json({
    primary: 'paqet',
    items: [
      { id: 'paqet', label: 'Paqet', version: 'v0.4.2', primary: true, profiles: ['raw-kcp', 'fast', 'fast2'], reverse: false },
      { id: 'backhaul', label: 'Backhaul', version: 'v0.6.5', primary: false, profiles: ['tcp', 'tcpmux', 'ws'], reverse: true },
      { id: 'gost', label: 'GOST', version: 'v3.0.0', primary: false, profiles: ['relay+tls', 'relay+ws'], reverse: false },
      { id: 'frp', label: 'FRP', version: 'v0.61.1', primary: false, profiles: ['xtcp', 'stcp'], reverse: true },
      { id: 'shadowsocks-2022', label: 'Shadowsocks 2022', version: 'builtin', primary: false, profiles: ['2022-blake3-aes-256-gcm'], reverse: false },
    ],
  })
})

plusRouter.get('/api/tunnels/nodes/:nodeId/engines', requireAuth, (req, res) => {
  res.json({
    node_id: Number(req.params.nodeId),
    items: [
      { id: 'paqet', available: true, compatible: true, capability: { installed: true } },
      { id: 'backhaul', available: true, compatible: true, capability: { installed: true } },
      { id: 'gost', available: true, compatible: true, capability: { installed: true } },
      { id: 'frp', available: true, compatible: true, capability: { installed: true } },
    ],
  })
})

plusRouter.put('/api/tunnels/:tunnelId', requireAuth, (req: AuthedRequest, res) => {
  const t = store.tunnels.find(item => item.id === Number(req.params.tunnelId))
  if (!t) {
    res.status(404).json({ detail: 'Tunnel not found' })
    return
  }
  const b = req.body || {}
  if (b.name !== undefined) t.name = String(b.name)
  if (b.source_node_id !== undefined) t.source_node_id = Number(b.source_node_id)
  if (b.exit_node_id !== undefined) t.exit_node_id = Number(b.exit_node_id)
  if (Array.isArray(b.inbound_tags)) t.inbound_tags = b.inbound_tags
  if (Array.isArray(b.domains)) t.domains = b.domains
  if (b.port !== undefined) t.port = Number(b.port)
  if (b.enabled !== undefined) t.enabled = Boolean(b.enabled)
  if (b.health_enabled !== undefined) t.health_enabled = Boolean(b.health_enabled)
  if (b.transport !== undefined) {
    t.transport = b.transport
    t.engine_id = b.transport
  }
  if (b.paqet_options !== undefined) t.paqet_options = b.paqet_options
  if (b.carrier_options !== undefined) t.carrier_options = b.carrier_options
  t.revision += 1
  store.tunnelAuditLogs.unshift({
    id: nextId(store.tunnelAuditLogs),
    created_at: new Date().toISOString(),
    actor: req.admin?.username || 'admin',
    action: 'update_tunnel',
    target_id: String(t.id),
    details: { revision: t.revision },
  })
  res.json(t)
})

plusRouter.delete('/api/tunnels/:tunnelId', requireAuth, (req: AuthedRequest, res) => {
  const id = Number(req.params.tunnelId)
  store.tunnels = store.tunnels.filter(t => t.id !== id)
  store.tunnelAuditLogs.unshift({
    id: nextId(store.tunnelAuditLogs),
    created_at: new Date().toISOString(),
    actor: req.admin?.username || 'admin',
    action: 'delete_tunnel',
    target_id: String(id),
    details: {},
  })
  res.json({ detail: 'Tunnel deleted' })
})

plusRouter.post('/api/tunnels/:tunnelId/rotate', requireAuth, (req, res) => {
  const t = store.tunnels.find(item => item.id === Number(req.params.tunnelId))
  if (!t) {
    res.status(404).json({ detail: 'Tunnel not found' })
    return
  }
  t.revision += 1
  res.json(t)
})

plusRouter.get('/api/tunnels/:tunnelId/paqet/status', requireAuth, (req, res) => {
  res.json({
    tunnel_id: Number(req.params.tunnelId),
    source: { active: true, state: 'running', pid: 14210, uptime_sec: 14820 },
    exit: { active: true, state: 'running', pid: 22814, uptime_sec: 14825 },
  })
})

plusRouter.post('/api/tunnels/:tunnelId/paqet/action', requireAuth, (req, res) => {
  res.json({
    ok: true,
    tunnel_id: Number(req.params.tunnelId),
    action: req.query.action || 'restart',
    side: req.query.side || 'both',
    status: 'running',
  })
})

plusRouter.get('/api/tunnels/:tunnelId/engine/status', requireAuth, (req, res) => {
  const t = store.tunnels.find(item => item.id === Number(req.params.tunnelId))
  res.json({
    tunnel_id: Number(req.params.tunnelId),
    engine: t?.engine_id || 'paqet',
    source: { active: true, state: 'running' },
    exit: { active: true, state: 'running' },
  })
})

plusRouter.post('/api/tunnels/:tunnelId/engine/action', requireAuth, (req, res) => {
  res.json({
    ok: true,
    tunnel_id: Number(req.params.tunnelId),
    action: req.query.action || 'restart',
    side: req.query.side || 'both',
  })
})

plusRouter.post('/api/tunnels/:tunnelId/hosts', requireAuth, (req, res) => {
  const tunnel = store.tunnels.find(t => t.id === Number(req.params.tunnelId))
  if (!tunnel) {
    res.status(404).json({ detail: 'Tunnel not found' })
    return
  }
  const sourceNode = store.nodes.find(n => n.id === tunnel.source_node_id)
  const createdHost = {
    id: nextId(store.hosts),
    native_tunnel_id: tunnel.id,
    remark: String(req.body?.remark || `${tunnel.name} Entry`),
    address: [String(req.body?.address || sourceNode?.address || '0.0.0.0')],
    inbound_tag: tunnel.inbound_tags[0] || 'VLESS_REALITY',
    port: Number(req.body?.port || 443),
    sni: null,
    host: null,
    path: null,
    security: 'inbound_default' as const,
    alpn: null,
    fingerprint: 'chrome',
    allowinsecure: false,
    is_disabled: false,
    http_headers: null,
    transport_settings: null,
    mux_settings: null,
    fragment_settings: null,
    noise_settings: null,
    random_user_agent: false,
    use_sni_as_host: false,
    vless_route: null,
    priority: store.hosts.length + 1,
    status: null,
    ech: null,
    pinned_peer_cert_sha256: null,
    verify_peer_cert_by_name: null,
    wireguard_overrides: null,
    subscription_templates: null,
    final_mask_settings: null,
    cipher_suites: null,
  }
  store.hosts.push(createdHost)
  res.status(201).json(createdHost)
})

plusRouter.post('/api/tunnels/:tunnelId/preflight', requireAuth, (req, res) => {
  const tunnel = store.tunnels.find(t => t.id === Number(req.params.tunnelId))
  if (!tunnel) {
    res.status(404).json({ detail: 'Tunnel not found' })
    return
  }
  res.json({
    can_apply: true,
    can_publish: true,
    checks: [
      { code: 'source_node_connected', status: 'pass', detail: `Source node #${tunnel.source_node_id} reachable` },
      { code: 'exit_node_connected', status: 'pass', detail: `Exit node #${tunnel.exit_node_id} reachable` },
      { code: 'port_binding', status: 'pass', detail: `Port ${tunnel.port} reserved and free` },
      { code: 'inbound_tags', status: 'pass', detail: `Inbounds (${tunnel.inbound_tags.join(', ')}) exist on core` },
    ],
  })
})

plusRouter.post('/api/tunnels/:tunnelId/health', requireAuth, (req, res) => {
  const tunnel = store.tunnels.find(t => t.id === Number(req.params.tunnelId))
  if (!tunnel) {
    res.status(404).json({ detail: 'Tunnel not found' })
    return
  }
  res.json({
    tunnel_id: tunnel.id,
    revision: tunnel.revision,
    checked_at: new Date().toISOString(),
    result: { healthy: true, status: 'healthy', tcp_rtt_ms: 29, udp_rtt_ms: 35, udp_loss_percent: 0, sample_mbps: 194.2 },
  })
})

// ---------- Tunnel Discovery & Provisioning ----------
plusRouter.get('/api/tunnel/discovery/reservations', requireAuth, (_req, res) => {
  res.json({ items: store.discoveryReservations })
})

plusRouter.post('/api/tunnel/discovery/reservations', requireAuth, (req, res) => {
  const address = String(req.body?.address || '0.0.0.0')
  const incoming = Array.isArray(req.body?.items) ? req.body.items : []
  const created: Array<Record<string, unknown>> = []
  for (const entry of incoming) {
    const row = {
      id: store.discoveryReservations.length + created.length + 1,
      address,
      name: String(entry?.name || 'reserved-service'),
      kind: String(entry?.kind || 'services'),
      role: entry?.role ?? null,
      peer: entry?.peer ?? null,
      ports: Array.isArray(entry?.ports) ? entry.ports : [],
      created_at: new Date().toISOString(),
    }
    store.discoveryReservations.unshift(row)
    created.push(row)
  }
  res.status(201).json({ items: created })
})

plusRouter.delete('/api/tunnel/discovery/reservations/:resId', requireAuth, (req, res) => {
  store.discoveryReservations = store.discoveryReservations.filter(r => String(r.id) !== req.params.resId)
  res.json({ detail: 'Reservation released' })
})

plusRouter.post('/api/tunnel/discovery/scan', requireAuth, (req, res) => {
  const host = String(req.body?.host || req.body?.address || '185.199.108.153')
  const registered = store.nodes.some(n => n.address === host)
  res.json({
    tunnels: [
      {
        kind: 'paqet',
        role: 'server',
        peer: null,
        listen: '0.0.0.0:24430',
        raw_port: 24430,
        ports: [{ port: 24430, protocol: 'udp', bind: '0.0.0.0', purpose: 'Paqet KCP carrier' }],
        running: true,
        process: { pid: 14210, name: 'paqet' },
        unit: { name: 'pasarguard-paqet.service', active: 'active', enabled: 'enabled' },
        firewall: { panel: 2, foreign: 0, chains: ['INPUT'] },
        interface: 'eth0',
      },
    ],
    proxies: [
      {
        config_path: '/var/lib/pasarguard/xray.json',
        inbounds: [{ tag: 'VLESS_REALITY', protocol: 'vless', port: 443 }],
        outbounds: [{ tag: 'direct', address: '0.0.0.0', port: 0 }],
      },
    ],
    links: [],
    dns_tunnels: [],
    dns_listeners: [{ proto: 'udp', bind: '0.0.0.0', port: 5353, name: 'smartdns' }],
    reservations: [
      { port: 443, protocol: 'tcp', bind: '0.0.0.0', purpose: 'Xray VLESS Reality', owner: 'xray' },
    ],
    warnings: [],
    conflicts: [],
    node_registered: registered,
    safe_to_proceed: true,
    privileged: true,
  })
})

plusRouter.get('/api/provision', requireAuth, (_req, res) => {
  res.json({
    items: store.provisionJobs,
    bundles: [
      { architecture: 'amd64', engines: ['paqet', 'backhaul', 'gost', 'frp'] },
      { architecture: 'arm64', engines: ['paqet', 'backhaul', 'gost', 'frp'] },
    ],
  })
})

plusRouter.post('/api/provision/inspect', requireAuth, (req, res) => {
  res.json({
    host: String(req.body?.host || ''),
    ssh_port: Number(req.body?.ssh_port || 22),
    reachable: true,
    server_host_key: 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPasarGuardPlusVerifiedHostKeyDemo',
    fingerprint: 'SHA256:PasarGuardPlusHostKeyFingerprintDemo',
    os: 'Ubuntu 24.04 LTS',
    arch: 'x86_64',
  })
})

plusRouter.post('/api/provision/install', requireAuth, (req, res) => {
  const b = req.body || {}
  const targetNodeId = b.node_id ? Number(b.node_id) : store.nodes[0]?.id ?? 1
  const job = {
    id: `prov_${crypto.randomBytes(4).toString('hex')}`,
    kind: 'install',
    target: String(b.host || b.name || '10.0.0.10'),
    state: 'ready',
    progress: 100,
    node_id: targetNodeId,
    result: {},
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  }
  store.provisionJobs.unshift(job)
  res.status(201).json(job)
})

plusRouter.post('/api/provision/activate/:identity', requireAuth, (req, res) => {
  const tunnelId = Number(req.params.identity)
  const tunnel = store.tunnels.find(t => t.id === tunnelId)
  if (tunnel) tunnel.applied_revision = tunnel.revision
  const job = {
    id: `act_${crypto.randomBytes(4).toString('hex')}`,
    kind: 'activate',
    target: tunnel?.name || `Tunnel #${req.params.identity}`,
    state: 'ready',
    progress: 100,
    node_id: tunnel?.source_node_id ?? null,
    result: {},
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  }
  store.provisionJobs.unshift(job)
  res.json(job)
})

plusRouter.get('/api/provision/jobs/:identity', requireAuth, (req, res) => {
  const job = store.provisionJobs.find(j => String(j.id) === req.params.identity) ?? {
    id: req.params.identity,
    kind: 'quick_tunnel',
    target: 'Tunnel',
    state: 'ready',
    progress: 100,
    result: { steps: [] },
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  }
  res.json(job)
})

plusRouter.post('/api/provision/jobs/:identity/cancel', requireAuth, (req, res) => {
  const job = store.provisionJobs.find(j => String(j.id) === req.params.identity)
  if (job) job.state = 'cancelled'
  res.json({ detail: 'Job cancelled' })
})

// ---------- Native DNS & Policies ----------
plusRouter.get('/api/dns/runtime', requireAuth, (_req, res) => {
  res.json({
    native: true,
    enabled: true,
    doh: '/dns-query',
    source_ip_transports: ['doh', 'udp', 'tcp'],
    upstream: '1.1.1.1',
    upstream_port: 53,
    worker_port: 5353,
    dot_port: 853,
    readiness: 'ready',
  })
})

plusRouter.get('/api/dns/catalog', requireAuth, (_req, res) => {
  res.json(buildCatalog())
})

plusRouter.get('/api/dns/audit', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 30)
  res.json(store.dnsAuditLogs.slice(offset, offset + limit))
})

plusRouter.get('/api/dns/policies', requireAuth, (_req, res) => {
  res.json(
    store.dnsPolicies.map(p => ({
      id: p.id,
      name: p.name,
      revision: p.revision,
      rules: p.rules.map(r => ({
        domain: r.domain,
        action: r.action === 'proxy' ? 'rewrite' : r.action,
        addresses: r.target ? [r.target] : [],
      })),
    }))
  )
})

plusRouter.post('/api/dns/policies', requireAuth, (req: AuthedRequest, res) => {
  const b = req.body || {}
  const policy: StoredDNSPolicy = {
    id: nextId(store.dnsPolicies),
    name: String(b.name || `Policy ${store.dnsPolicies.length + 1}`),
    revision: 1,
    rules: Array.isArray(b.rules)
      ? b.rules.map((r: Record<string, unknown>) => ({
          domain: String(r.domain || ''),
          action: String(r.action || 'allow'),
          target: Array.isArray(r.addresses) && r.addresses[0] ? String(r.addresses[0]) : null,
        }))
      : [],
  }
  store.dnsPolicies.push(policy)
  store.dnsAuditLogs.unshift({
    id: nextId(store.dnsAuditLogs),
    created_at: new Date().toISOString(),
    actor: req.admin?.username || 'admin',
    action: 'create_policy',
    target: policy.name,
    detail: `${policy.rules.length} rules`,
  })
  res.status(201).json(policy)
})

plusRouter.post('/api/dns/policies/from-catalog', requireAuth, (req: AuthedRequest, res) => {
  const b = req.body || {}
  const relayTarget = Array.isArray(b.relay_addresses) && b.relay_addresses[0] ? String(b.relay_addresses[0]) : '185.199.108.153'
  const groups: Array<{ service?: string; group?: string }> = Array.isArray(b.groups) ? b.groups : []
  const services: string[] = Array.isArray(b.services) ? b.services : groups.map(g => String(g.service || '')).filter(Boolean)
  const rules = services.map(svc => ({
    domain: `${svc}.com`,
    action: 'rewrite',
    target: relayTarget,
    services: [svc],
  }))
  const policy: StoredDNSPolicy = {
    id: nextId(store.dnsPolicies),
    name: String(b.name || 'Catalog DNS Policy'),
    revision: 1,
    rules,
  }
  store.dnsPolicies.push(policy)
  res.status(201).json(policy)
})

plusRouter.put('/api/dns/policies/:policyId', requireAuth, (req, res) => {
  const policy = store.dnsPolicies.find(p => p.id === Number(req.params.policyId))
  if (!policy) {
    res.status(404).json({ detail: 'Policy not found' })
    return
  }
  const b = req.body || {}
  if (b.name !== undefined) policy.name = String(b.name)
  if (Array.isArray(b.rules)) {
    policy.rules = b.rules.map((r: Record<string, unknown>) => ({
      domain: String(r.domain || ''),
      action: String(r.action || 'allow'),
      target: Array.isArray(r.addresses) && r.addresses[0] ? String(r.addresses[0]) : null,
    }))
  }
  policy.revision += 1
  res.json(policy)
})

plusRouter.delete('/api/dns/policies/:policyId', requireAuth, (req, res) => {
  const id = Number(req.params.policyId)
  store.dnsPolicies = store.dnsPolicies.filter(p => p.id !== id)
  res.json({ detail: 'Policy deleted' })
})

plusRouter.get('/api/dns/accounts', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 20)
  const search = String(req.query.search || '').toLowerCase()
  let items = store.dnsAccounts
  if (search) {
    items = items.filter(a => a.username.toLowerCase().includes(search) || a.policy_name.toLowerCase().includes(search))
  }
  res.json({ items: items.slice(offset, offset + limit), total: items.length })
})

plusRouter.post('/api/dns/accounts', requireAuth, (req: AuthedRequest, res) => {
  const b = req.body || {}
  const user = store.users.find(u => u.id === Number(b.user_id)) ?? store.users[0]
  const policy = store.dnsPolicies.find(p => p.id === Number(b.policy_id)) ?? store.dnsPolicies[0]
  const account: StoredDNSAccount = {
    id: nextId(store.dnsAccounts),
    user_id: user.id,
    username: user.username,
    policy_id: policy?.id ?? 1,
    policy_name: policy?.name ?? 'Default Policy',
    enabled: b.enabled !== undefined ? Boolean(b.enabled) : true,
    eligible: true,
    query_limit: Number(b.query_limit || 100000),
    qps: Number(b.qps || 30),
    period: Number(b.period || 86400),
    queries: 0,
    request_bytes: 0,
    response_bytes: 0,
    errors: 0,
    blocked: 0,
    relay_rps: Number(b.relay_rps || 50),
    relay_enabled: b.relay_enabled !== undefined ? Boolean(b.relay_enabled) : true,
    relay_byte_limit: Number(b.relay_byte_limit || 50 * 1024 ** 3),
    relay_bytes: 0,
    relay_reserved: 0,
    relay_uncertain_bytes: 0,
    token: `dns_tok_${crypto.randomBytes(8).toString('hex')}`,
    created_at: new Date().toISOString(),
  }
  store.dnsAccounts.push(account)
  store.dnsAuditLogs.unshift({
    id: nextId(store.dnsAuditLogs),
    created_at: new Date().toISOString(),
    actor: req.admin?.username || 'admin',
    action: 'create_dns_account',
    target: account.username,
    detail: `Policy ${account.policy_name}`,
  })
  res.status(201).json(account)
})

plusRouter.put('/api/dns/accounts/:accountId', requireAuth, (req, res) => {
  const acc = store.dnsAccounts.find(a => a.id === Number(req.params.accountId))
  if (!acc) {
    res.status(404).json({ detail: 'DNS account not found' })
    return
  }
  const b = req.body || {}
  if (b.policy_id !== undefined) {
    acc.policy_id = Number(b.policy_id)
    acc.policy_name = store.dnsPolicies.find(p => p.id === acc.policy_id)?.name ?? acc.policy_name
  }
  if (b.enabled !== undefined) acc.enabled = Boolean(b.enabled)
  if (b.query_limit !== undefined) acc.query_limit = Number(b.query_limit)
  if (b.qps !== undefined) acc.qps = Number(b.qps)
  if (b.period !== undefined) acc.period = Number(b.period)
  if (b.relay_enabled !== undefined) acc.relay_enabled = Boolean(b.relay_enabled)
  if (b.relay_byte_limit !== undefined) acc.relay_byte_limit = Number(b.relay_byte_limit)
  if (b.relay_rps !== undefined) acc.relay_rps = Number(b.relay_rps)
  res.json(acc)
})

plusRouter.delete('/api/dns/accounts/:accountId', requireAuth, (req, res) => {
  const id = Number(req.params.accountId)
  store.dnsAccounts = store.dnsAccounts.filter(a => a.id !== id)
  res.json({ detail: 'DNS account deleted' })
})

plusRouter.post('/api/dns/accounts/:accountId/reset', requireAuth, (req, res) => {
  const acc = store.dnsAccounts.find(a => a.id === Number(req.params.accountId))
  if (!acc) {
    res.status(404).json({ detail: 'DNS account not found' })
    return
  }
  acc.queries = 0
  acc.request_bytes = 0
  acc.response_bytes = 0
  acc.relay_bytes = 0
  res.json(acc)
})

plusRouter.post('/api/dns/accounts/:accountId/rotate', requireAuth, (req, res) => {
  const acc = store.dnsAccounts.find(a => a.id === Number(req.params.accountId))
  if (!acc) {
    res.status(404).json({ detail: 'DNS account not found' })
    return
  }
  acc.token = `dns_tok_${crypto.randomBytes(10).toString('hex')}`
  res.json({ token: acc.token })
})

plusRouter.post('/api/dns/accounts/:accountId/credentials', requireAuth, (req, res) => {
  const acc = store.dnsAccounts.find(a => a.id === Number(req.params.accountId))
  if (!acc) {
    res.status(404).json({ detail: 'DNS account not found' })
    return
  }
  res.json({ token: acc.token })
})

plusRouter.get('/api/dns/accounts/:accountId/portal', requireAuth, (req, res) => {
  const acc = store.dnsAccounts.find(a => a.id === Number(req.params.accountId))
  const user = store.users.find(u => u.id === acc?.user_id)
  const token = user?.subscription_token ?? 'sub_alex_pro_01'
  res.json({ url: `/sub/${token}/dns` })
})

plusRouter.get('/api/dns/accounts/:accountId/periods', requireAuth, (req, res) => {
  const now = Date.now()
  res.json(
    Array.from({ length: 5 }, (_, i) => ({
      id: i + 1,
      period: 5 - i,
      started_at: new Date(now - (i + 1) * 86400_000).toISOString(),
      ended_at: i === 0 ? null : new Date(now - i * 86400_000).toISOString(),
      queries: 2800 + i * 310,
      blocked: 140 + i * 25,
      relay_bytes: (i + 1) * 1024 ** 3,
    }))
  )
})

plusRouter.get('/api/dns/accounts/:accountId/leases', requireAuth, (req, res) => {
  const accountId = Number(req.params.accountId)
  res.json(store.dnsLeases.filter(l => l.account_id === accountId))
})

plusRouter.post('/api/dns/accounts/:accountId/leases', requireAuth, (req, res) => {
  const accountId = Number(req.params.accountId)
  const ttl = Number(req.body?.ttl || 86400)
  const lease = {
    id: nextId(store.dnsLeases),
    account_id: accountId,
    ip: String(req.body?.ip || '192.0.2.10'),
    label: req.body?.label ?? 'Manual Lease',
    created_at: new Date().toISOString(),
    expires_at: new Date(Date.now() + ttl * 1000).toISOString(),
  }
  store.dnsLeases.push(lease)
  res.status(201).json(lease)
})

plusRouter.delete('/api/dns/accounts/:accountId/leases/:ip', requireAuth, (req, res) => {
  const accountId = Number(req.params.accountId)
  const ip = decodeURIComponent(req.params.ip)
  store.dnsLeases = store.dnsLeases.filter(l => !(l.account_id === accountId && l.ip === ip))
  res.json({ detail: 'Lease revoked' })
})

plusRouter.get('/api/dns/users/:userId', requireAuth, (req, res) => {
  const userId = Number(req.params.userId)
  const acc = store.dnsAccounts.find(a => a.user_id === userId) ?? null
  res.json({
    account: acc,
    leases: acc ? store.dnsLeases.filter(l => l.account_id === acc.id) : [],
    profiles: store.dnsPolicies.map(p => ({ policy_id: p.id, name: p.name })),
  })
})

plusRouter.all('/dns-query', (_req, res) => {
  res.json({ Status: 0, Answer: [{ name: 'example.com.', type: 1, TTL: 60, data: '93.184.216.34' }] })
})

// ---------- SmartDNS & Gaming ----------
plusRouter.get('/api/smartdns', requireAuth, (_req, res) => {
  const nodes = store.nodes.map(n => {
    const cfg = store.smartDnsNodeSettings.get(n.id) ?? { enabled: false, bind_address: '0.0.0.0', port: 5353, revision: 1, applied_revision: 1 }
    return {
      node_id: n.id,
      revision: cfg.revision,
      applied_revision: cfg.applied_revision,
      result: { ok: true },
      dns: { enabled: cfg.enabled, bind_address: cfg.bind_address, port: cfg.port },
    }
  })
  res.json({ routes: store.smartDnsRoutes, nodes })
})

plusRouter.post('/api/smartdns/routes', requireAuth, (req, res) => {
  const b = req.body || {}
  const route: StoredSmartDNSRoute = {
    id: nextId(store.smartDnsRoutes),
    node_id: Number(b.node_id || 1),
    policy_id: Number(b.policy_id || 1),
    domain: String(b.domain || 'example.com'),
    public_address: String(b.public_address || '185.199.108.153'),
    bind_address: String(b.bind_address || '0.0.0.0'),
    tcp_ports: Array.isArray(b.tcp_ports) ? b.tcp_ports.map(Number) : [80, 443],
    udp_ports: Array.isArray(b.udp_ports) ? b.udp_ports.map(Number) : [],
    mode: b.mode === 'fixed' ? 'fixed' : 'sniff',
    enabled: b.enabled !== undefined ? Boolean(b.enabled) : true,
    revision: 1,
    egress_tunnel_id: b.egress_tunnel_id ? Number(b.egress_tunnel_id) : null,
  }
  store.smartDnsRoutes.push(route)
  res.status(201).json(route)
})

plusRouter.put('/api/smartdns/routes/:routeId', requireAuth, (req, res) => {
  const route = store.smartDnsRoutes.find(r => r.id === Number(req.params.routeId))
  if (!route) {
    res.status(404).json({ detail: 'Route not found' })
    return
  }
  const b = req.body || {}
  if (b.node_id !== undefined) route.node_id = Number(b.node_id)
  if (b.policy_id !== undefined) route.policy_id = Number(b.policy_id)
  if (b.domain !== undefined) route.domain = String(b.domain)
  if (b.public_address !== undefined) route.public_address = String(b.public_address)
  if (b.bind_address !== undefined) route.bind_address = String(b.bind_address)
  if (Array.isArray(b.tcp_ports)) route.tcp_ports = b.tcp_ports.map(Number)
  if (Array.isArray(b.udp_ports)) route.udp_ports = b.udp_ports.map(Number)
  if (b.mode !== undefined) route.mode = b.mode === 'fixed' ? 'fixed' : 'sniff'
  if (b.enabled !== undefined) route.enabled = Boolean(b.enabled)
  if (b.egress_tunnel_id !== undefined) route.egress_tunnel_id = b.egress_tunnel_id ? Number(b.egress_tunnel_id) : null
  route.revision += 1
  res.json(route)
})

plusRouter.delete('/api/smartdns/routes/:routeId', requireAuth, (req, res) => {
  const id = Number(req.params.routeId)
  store.smartDnsRoutes = store.smartDnsRoutes.filter(r => r.id !== id)
  res.json({ detail: 'Route deleted' })
})

plusRouter.post('/api/smartdns/nodes/:nodeId/apply', requireAuth, (req, res) => {
  const nodeId = Number(req.params.nodeId)
  const cfg = store.smartDnsNodeSettings.get(nodeId)
  if (cfg) cfg.applied_revision = cfg.revision
  res.json({ ok: true, node_id: nodeId })
})

plusRouter.put('/api/smartdns/nodes/:nodeId/dns', requireAuth, (req, res) => {
  const nodeId = Number(req.params.nodeId)
  const prev = store.smartDnsNodeSettings.get(nodeId) ?? { enabled: true, bind_address: '0.0.0.0', port: 5353, revision: 0, applied_revision: 0 }
  const b = req.body || {}
  const updated = {
    enabled: b.enabled !== undefined ? Boolean(b.enabled) : prev.enabled,
    bind_address: String(b.bind_address || prev.bind_address),
    port: Number(b.port || prev.port),
    revision: prev.revision + 1,
    applied_revision: prev.revision + 1,
  }
  store.smartDnsNodeSettings.set(nodeId, updated)
  res.json(updated)
})

plusRouter.put('/api/smartdns/accounts/:accountId', requireAuth, (req, res) => {
  const acc = store.dnsAccounts.find(a => a.id === Number(req.params.accountId))
  if (!acc) {
    res.status(404).json({ detail: 'DNS account not found' })
    return
  }
  const b = req.body || {}
  if (b.relay_enabled !== undefined) acc.relay_enabled = Boolean(b.relay_enabled)
  if (b.relay_byte_limit !== undefined) acc.relay_byte_limit = Number(b.relay_byte_limit)
  if (b.relay_rps !== undefined) acc.relay_rps = Number(b.relay_rps)
  res.json(acc)
})

plusRouter.post('/api/smartdns/nodes/:nodeId/reserve', requireAuth, (_req, res) => {
  res.json({ ok: true, reserved: 0 })
})

plusRouter.post('/api/smartdns/nodes/:nodeId/settle', requireAuth, (_req, res) => {
  res.json({ ok: true, settled: 0 })
})

plusRouter.post('/api/smartdns/nodes/:nodeId/resolve', requireAuth, (req, res) => {
  res.json({ ok: true, domain: req.body?.domain || 'example.com', addresses: ['93.184.216.34'] })
})

// Gaming DNS Wizard
plusRouter.get('/api/smartdns/gaming/profiles', requireAuth, (_req, res) => {
  const items = store.dnsPolicies.map(p => {
    const routes = store.smartDnsRoutes.filter(r => r.policy_id === p.id)
    const accounts = store.dnsAccounts.filter(a => a.policy_id === p.id).length
    const firstRoute = routes[0]
    return {
      policy_id: p.id,
      name: p.name,
      node_id: firstRoute?.node_id ?? 1,
      routes: routes.length || p.rules.length,
      applied: true,
      accounts,
      address: firstRoute?.public_address ?? '185.199.108.153',
    }
  })
  res.json({ items })
})

plusRouter.post('/api/smartdns/gaming/plan', requireAuth, (req, res) => {
  const b = req.body || {}
  const services: string[] = Array.isArray(b.services) ? b.services : []
  res.json({
    ok: true,
    blockers: [],
    warnings: b.egress_tunnel_id ? ['udp_not_covered'] : ['direct_egress', 'udp_not_covered'],
    routes: services.length * 2,
    policy: { name: String(b.name || 'Gaming DNS'), domains: services.length * 12 },
    coverage: services.map(s => ({ service: s, label: s, domains: 12, routes: 2 })),
    dns: { will_enable: true, bind_address: '0.0.0.0', port: 5353 },
  })
})

plusRouter.post('/api/smartdns/gaming', requireAuth, (req, res) => {
  const b = req.body || {}
  const nodeId = Number(b.node_id || 1)
  const services: string[] = Array.isArray(b.services) ? b.services : ['steam']
  const policy: StoredDNSPolicy = {
    id: nextId(store.dnsPolicies),
    name: String(b.name || 'Gaming DNS'),
    revision: 1,
    rules: services.map(s => ({ domain: `${s}.com`, action: 'rewrite', target: String(b.public_address || '185.199.108.153'), services: [s] })),
  }
  store.dnsPolicies.push(policy)
  const route: StoredSmartDNSRoute = {
    id: nextId(store.smartDnsRoutes),
    node_id: nodeId,
    policy_id: policy.id,
    domain: `${services[0] || 'gaming'}.com`,
    public_address: String(b.public_address || '185.199.108.153'),
    bind_address: '0.0.0.0',
    tcp_ports: [80, 443],
    udp_ports: [],
    mode: 'sniff',
    enabled: true,
    revision: 1,
    egress_tunnel_id: b.egress_tunnel_id ? Number(b.egress_tunnel_id) : null,
  }
  store.smartDnsRoutes.push(route)
  res.status(201).json({
    policy_id: policy.id,
    node_id: nodeId,
    route_ids: [route.id],
    revision: 1,
    applied: true,
    detail: null,
  })
})

plusRouter.post('/api/smartdns/gaming/:policyId/attach', requireAuth, (req, res) => {
  const policyId = Number(req.params.policyId)
  const userId = Number(req.body?.user_id || 1)
  const user = store.users.find(u => u.id === userId) ?? store.users[0]
  const policy = store.dnsPolicies.find(p => p.id === policyId) ?? store.dnsPolicies[0]
  let acc = store.dnsAccounts.find(a => a.user_id === user.id && a.policy_id === policy.id)
  if (!acc) {
    acc = {
      id: nextId(store.dnsAccounts),
      user_id: user.id,
      username: user.username,
      policy_id: policy.id,
      policy_name: policy.name,
      enabled: true,
      eligible: true,
      query_limit: 250000,
      qps: 60,
      period: 86400,
      queries: 0,
      request_bytes: 0,
      response_bytes: 0,
      errors: 0,
      blocked: 0,
      relay_rps: 100,
      relay_enabled: true,
      relay_byte_limit: Number(req.body?.byte_limit || 50 * 1024 ** 3),
      relay_bytes: 0,
      relay_reserved: 0,
      relay_uncertain_bytes: 0,
      token: `dns_tok_${crypto.randomBytes(8).toString('hex')}`,
      created_at: new Date().toISOString(),
    }
    store.dnsAccounts.push(acc)
  }
  const clientIps: string[] = Array.isArray(req.body?.client_ips) ? req.body.client_ips : []
  for (const ip of clientIps) {
    store.dnsLeases.push({
      id: nextId(store.dnsLeases),
      account_id: acc.id,
      ip,
      label: 'Gaming Client',
      created_at: new Date().toISOString(),
      expires_at: new Date(Date.now() + 30 * 86400_000).toISOString(),
    })
  }
  res.json({ account_id: acc.id, portal_path: `/sub/${user.subscription_token}/dns` })
})
