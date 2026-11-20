import crypto from 'node:crypto'
import { Router } from 'express'
import {
  buildSystemResourceStats,
  buildSystemUsersStats,
  buildUserResponse,
  generateTimeBuckets,
  getAllInboundsFromCores,
  nextId,
  store,
  type StoredClientTemplate,
  type StoredCore,
  type StoredGroup,
  type StoredHost,
  type StoredNode,
  type StoredUser,
  type StoredUserTemplate,
} from '../store'
import { requireAuth, type AuthedRequest } from './auth'

export const panelRouter = Router()

function findUser(param: string): StoredUser | undefined {
  const num = Number(param)
  if (!Number.isNaN(num) && /^\d+$/.test(param)) {
    return store.users.find(u => u.id === num) ?? store.users.find(u => u.username === param)
  }
  return store.users.find(u => u.username === param)
}

function buildSubscriptionLinks(user: StoredUser): string[] {
  const vlessId = (user.proxy_settings?.vless as { id?: string } | undefined)?.id || crypto.randomUUID()
  const trojanPw = (user.proxy_settings?.trojan as { password?: string } | undefined)?.password || 'trojan_pass'
  const links: string[] = []
  for (const host of store.hosts) {
    if (host.is_disabled) continue
    const addr = host.address[0] || 'example.net'
    const port = host.port || 443
    const remark = encodeURIComponent(`${host.remark}-${user.username}`)
    if (host.inbound_tag?.includes('VLESS')) {
      const sni = host.sni?.[0] || 'www.cloudflare.com'
      links.push(`vless://${vlessId}@${addr}:${port}?security=reality&encryption=none&flow=xtls-rprx-vision&type=tcp&sni=${sni}&fp=${host.fingerprint || 'chrome'}#${remark}`)
    } else if (host.inbound_tag?.includes('TROJAN')) {
      const path = encodeURIComponent(host.path || '/ws-trojan')
      links.push(`trojan://${trojanPw}@${addr}:${port}?security=tls&type=ws&path=${path}#${remark}`)
    }
  }
  if (links.length === 0) {
    links.push(`vless://${vlessId}@185.199.108.153:443?security=reality&encryption=none&type=tcp#${encodeURIComponent(user.username)}`)
  }
  return links
}

// ---------- System & Infrastructure ----------
panelRouter.get('/api/system', requireAuth, (req, res) => {
  const admin = req.query.admin ? String(req.query.admin) : undefined
  res.json({
    ...buildSystemUsersStats(admin),
    ...buildSystemResourceStats(),
  })
})

panelRouter.get('/api/system/stats', requireAuth, (req, res) => {
  const admin = req.query.admin ? String(req.query.admin) : undefined
  res.json({
    ...buildSystemUsersStats(admin),
    ...buildSystemResourceStats(),
  })
})

panelRouter.get('/api/system/resources', requireAuth, (_req, res) => {
  res.json(buildSystemResourceStats())
})

panelRouter.get('/api/system/users', requireAuth, (req, res) => {
  const admin = req.query.admin ? String(req.query.admin) : undefined
  res.json(buildSystemUsersStats(admin))
})

panelRouter.get('/api/inbounds', requireAuth, (_req, res) => {
  res.json(getAllInboundsFromCores().map(i => i.tag))
})

panelRouter.get('/api/inbounds/details', requireAuth, (_req, res) => {
  res.json({ inbounds: getAllInboundsFromCores() })
})

panelRouter.get('/api/wireguard/subnets', requireAuth, (_req, res) => {
  res.json([
    {
      subnet: '10.10.0.0/24',
      interface_tags: ['wg0', 'WG_XRAY'],
      capacity: 253,
      used: store.users.filter(u => u.proxy_settings?.wireguard).length + 1,
      free: 251,
      free_ips: ['10.10.0.10/32', '10.10.0.11/32', '10.10.0.12/32', '10.10.0.13/32'],
    },
  ])
})

panelRouter.get('/api/workers/health', requireAuth, (_req, res) => {
  const now = new Date().toISOString()
  res.json({
    scheduler: { status: 'healthy', running: true, last_heartbeat: now, active_jobs: 0 },
    node: { status: 'healthy', running: true, last_heartbeat: now, connected_nodes: store.nodes.filter(n => n.status === 'connected').length },
  })
})

// ---------- Settings ----------
panelRouter.get('/api/settings', requireAuth, (_req, res) => {
  res.json(store.settings)
})

panelRouter.put('/api/settings', requireAuth, (req, res) => {
  const b = req.body || {}
  for (const [k, v] of Object.entries(b)) {
    if (v && typeof v === 'object' && !Array.isArray(v) && store.settings[k] && typeof store.settings[k] === 'object') {
      store.settings[k] = { ...(store.settings[k] as Record<string, unknown>), ...(v as Record<string, unknown>) }
    } else if (v !== undefined) {
      store.settings[k] = v
    }
  }
  res.json(store.settings)
})

panelRouter.get('/api/settings/general', requireAuth, (_req, res) => {
  res.json(store.settings.general)
})

// ---------- Users ----------
panelRouter.get('/api/users', requireAuth, (req: AuthedRequest, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 20)
  const search = String(req.query.search || req.query.username || '').toLowerCase()
  const statusParam = req.query.status
  const statusFilter = Array.isArray(statusParam)
    ? statusParam.map(String)
    : typeof statusParam === 'string' && statusParam
      ? [statusParam]
      : null
  const adminFilter = req.query.admin ? (Array.isArray(req.query.admin) ? req.query.admin.map(String) : [String(req.query.admin)]) : null
  const sort = String(req.query.sort || '-created_at')

  let users = [...store.users]
  if (search) {
    users = users.filter(u => u.username.toLowerCase().includes(search) || (u.note || '').toLowerCase().includes(search))
  }
  if (statusFilter && statusFilter.length > 0) {
    users = users.filter(u => statusFilter.includes(u.status))
  }
  if (adminFilter && adminFilter.length > 0) {
    const adminIds = store.admins.filter(a => adminFilter.includes(a.username)).map(a => a.id)
    users = users.filter(u => adminIds.includes(u.admin_id))
  }

  const desc = sort.startsWith('-')
  const key = desc ? sort.slice(1) : sort
  users.sort((a, b) => {
    const va = (a as unknown as Record<string, unknown>)[key]
    const vb = (b as unknown as Record<string, unknown>)[key]
    const cmp = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va ?? '').localeCompare(String(vb ?? ''))
    return desc ? -cmp : cmp
  })

  res.json({
    users: users.slice(offset, offset + limit).map(buildUserResponse),
    total: users.length,
  })
})

panelRouter.get('/api/users/simple', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 100)
  const search = String(req.query.search || req.query.username || '').toLowerCase()
  let users = store.users
  if (search) users = users.filter(u => u.username.toLowerCase().includes(search))
  res.json({
    users: users.slice(offset, offset + limit).map(u => ({ id: u.id, username: u.username, status: u.status })),
    total: users.length,
  })
})

panelRouter.get('/api/users/usage', requireAuth, (req, res) => {
  const period = String(req.query.period || 'hour')
  const { start, end, buckets } = generateTimeBuckets(req.query.start as string | undefined, req.query.end as string | undefined, period)
  const list = buckets.map((b, i) => ({
    period_start: b,
    total_traffic: Math.round((1.4 + Math.sin(i * 0.7) * 0.6 + (i % 4) * 0.25) * 1024 ** 3),
  }))
  res.json({ period, start, end, stats: { all: list } })
})

panelRouter.get('/api/users/counts/:metric', requireAuth, (req, res) => {
  const period = String(req.query.period || 'day')
  const metric = req.params.metric
  const { start, end, buckets } = generateTimeBuckets(req.query.start as string | undefined, req.query.end as string | undefined, period)
  const list = buckets.map((b, i) => ({
    period_start: b,
    count: Math.max(0, Math.round(store.users.length + Math.sin(i) * 2)),
  }))
  res.json({
    period,
    start,
    end,
    metric,
    count_during_period: list.reduce((s, x) => s + x.count, 0),
    stats: { [metric]: list },
  })
})

panelRouter.get('/api/users/sub_update/chart', requireAuth, (req, res) => {
  const period = String(req.query.period || 'day')
  const { start, end, buckets } = generateTimeBuckets(req.query.start as string | undefined, req.query.end as string | undefined, period)
  const agents = [
    { name: 'v2rayNG', count: 42, percentage: 38.2 },
    { name: 'Streisand', count: 31, percentage: 28.2 },
    { name: 'sing-box', count: 22, percentage: 20.0 },
    { name: 'FlClash', count: 15, percentage: 13.6 },
  ]
  const stats = buckets.flatMap((b, i) =>
    agents.map(a => ({
      period_start: b,
      agent: a.name,
      count: Math.max(1, Math.round(a.count / 10 + (i % 3))),
    }))
  )
  res.json({ period, start, end, total: 110, segments: agents, stats })
})

panelRouter.get('/api/users/expired', requireAuth, (_req, res) => {
  res.json(store.users.filter(u => u.status === 'expired').map(u => u.username))
})

panelRouter.delete('/api/users/expired', requireAuth, (_req, res) => {
  const expired = store.users.filter(u => u.status === 'expired').map(u => u.username)
  store.users = store.users.filter(u => u.status !== 'expired')
  res.json(expired)
})

panelRouter.post('/api/users/reset', requireAuth, (_req, res) => {
  for (const u of store.users) {
    u.used_traffic = 0
    if (u.status === 'limited') u.status = 'active'
  }
  res.json({ detail: 'All users traffic reset' })
})

// Bulk User operations
panelRouter.post('/api/users/bulk/delete', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.user_ids || []).map(Number))
  const usernames = new Set<string>(req.body?.usernames || [])
  store.users = store.users.filter(u => !ids.has(u.id) && !usernames.has(u.username))
  res.json({ detail: 'Users deleted' })
})

panelRouter.post('/api/users/bulk/reset', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.user_ids || []).map(Number))
  const usernames = new Set<string>(req.body?.usernames || [])
  for (const u of store.users) {
    if (ids.has(u.id) || usernames.has(u.username)) {
      u.used_traffic = 0
      if (u.status === 'limited') u.status = 'active'
    }
  }
  res.json({ detail: 'Usage reset' })
})

panelRouter.post('/api/users/bulk/disable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.user_ids || []).map(Number))
  const usernames = new Set<string>(req.body?.usernames || [])
  for (const u of store.users) {
    if (ids.has(u.id) || usernames.has(u.username)) u.status = 'disabled'
  }
  res.json({ detail: 'Users disabled' })
})

panelRouter.post('/api/users/bulk/enable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.user_ids || []).map(Number))
  const usernames = new Set<string>(req.body?.usernames || [])
  for (const u of store.users) {
    if ((ids.has(u.id) || usernames.has(u.username)) && u.status === 'disabled') u.status = 'active'
  }
  res.json({ detail: 'Users enabled' })
})

panelRouter.post('/api/users/bulk/revoke_sub', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.user_ids || []).map(Number))
  const usernames = new Set<string>(req.body?.usernames || [])
  for (const u of store.users) {
    if (ids.has(u.id) || usernames.has(u.username)) {
      const token = `sub_${crypto.randomBytes(6).toString('hex')}`
      u.subscription_token = token
      u.subscription_url = `/sub/${token}`
    }
  }
  res.json({ detail: 'Subscriptions revoked' })
})

panelRouter.post('/api/users/bulk/set_owner', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.user_ids || []).map(Number))
  const owner = store.admins.find(a => a.username === req.body?.admin || a.id === Number(req.body?.admin_id))
  if (owner) {
    for (const u of store.users) {
      if (ids.has(u.id)) u.admin_id = owner.id
    }
  }
  res.json({ detail: 'Owner updated' })
})

panelRouter.post('/api/users/bulk/expire', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.user_ids || []).map(Number))
  const groups = new Set<number>((req.body?.group_ids || []).map(Number))
  const deltaDays = Number(req.body?.days || 0)
  for (const u of store.users) {
    if (ids.size > 0 && !ids.has(u.id)) continue
    if (groups.size > 0 && !u.group_ids.some(g => groups.has(g))) continue
    if (req.body?.expire !== undefined) {
      u.expire = req.body.expire
    } else if (deltaDays !== 0) {
      const base = u.expire ? new Date(u.expire).getTime() : Date.now()
      u.expire = new Date(base + deltaDays * 86400_000).toISOString()
    }
  }
  res.json({ detail: 'Expire updated' })
})

panelRouter.post('/api/users/bulk/data_limit', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.user_ids || []).map(Number))
  const groups = new Set<number>((req.body?.group_ids || []).map(Number))
  for (const u of store.users) {
    if (ids.size > 0 && !ids.has(u.id)) continue
    if (groups.size > 0 && !u.group_ids.some(g => groups.has(g))) continue
    if (req.body?.data_limit !== undefined) u.data_limit = req.body.data_limit !== null ? Number(req.body.data_limit) : null
  }
  res.json({ detail: 'Data limit updated' })
})

panelRouter.post('/api/users/bulk/proxy_settings', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.user_ids || []).map(Number))
  for (const u of store.users) {
    if (ids.size > 0 && !ids.has(u.id)) continue
    if (req.body?.proxy_settings) {
      u.proxy_settings = { ...u.proxy_settings, ...req.body.proxy_settings }
    }
  }
  res.json({ detail: 'Proxy settings updated' })
})

panelRouter.post('/api/user/from_template', requireAuth, (req: AuthedRequest, res) => {
  const tpl = store.userTemplates.find(t => t.id === Number(req.body?.user_template_id)) ?? store.userTemplates[0]
  const username = String(req.body?.username || `${tpl?.username_prefix || 'user_'}${store.users.length + 1}${tpl?.username_suffix || ''}`)
  const token = `sub_${crypto.randomBytes(6).toString('hex')}`
  const created: StoredUser = {
    id: nextId(store.users),
    username,
    status: tpl?.status || 'active',
    used_traffic: 0,
    lifetime_used_traffic: 0,
    data_limit: tpl?.data_limit ?? null,
    data_limit_reset_strategy: tpl?.data_limit_reset_strategy ?? 'no_reset',
    expire: tpl?.expire_duration ? new Date(Date.now() + tpl.expire_duration * 1000).toISOString() : null,
    on_hold_expire_duration: tpl?.status === 'on_hold' ? tpl.expire_duration : null,
    on_hold_timeout: null,
    group_ids: tpl?.group_ids ?? [1],
    auto_delete_in_days: null,
    hwid_limit: tpl?.hwid_limit ?? null,
    note: req.body?.note ?? null,
    next_plan: null,
    proxy_settings: { vless: { id: crypto.randomUUID(), flow: 'xtls-rprx-vision' } },
    created_at: new Date().toISOString(),
    edit_at: null,
    online_at: null,
    sub_updated_at: null,
    sub_last_user_agent: null,
    subscription_token: token,
    subscription_url: `/sub/${token}`,
    admin_id: req.admin!.id,
  }
  store.users.push(created)
  res.status(201).json(buildUserResponse(created))
})

panelRouter.post('/api/users/bulk/from_template', requireAuth, (req: AuthedRequest, res) => {
  const count = Math.min(50, Math.max(1, Number(req.body?.count || 1)))
  const created = []
  for (let i = 0; i < count; i++) {
    const token = `sub_${crypto.randomBytes(6).toString('hex')}`
    const u: StoredUser = {
      id: nextId(store.users),
      username: `bulk_${Date.now()}_${i}`,
      status: 'active',
      used_traffic: 0,
      lifetime_used_traffic: 0,
      data_limit: 50 * 1024 ** 3,
      data_limit_reset_strategy: 'no_reset',
      expire: null,
      on_hold_expire_duration: null,
      on_hold_timeout: null,
      group_ids: [1],
      auto_delete_in_days: null,
      hwid_limit: null,
      note: null,
      next_plan: null,
      proxy_settings: { vless: { id: crypto.randomUUID(), flow: 'xtls-rprx-vision' } },
      created_at: new Date().toISOString(),
      edit_at: null,
      online_at: null,
      sub_updated_at: null,
      sub_last_user_agent: null,
      subscription_token: token,
      subscription_url: `/sub/${token}`,
      admin_id: req.admin!.id,
    }
    store.users.push(u)
    created.push(buildUserResponse(u))
  }
  res.status(201).json(created)
})

panelRouter.post('/api/users/bulk/apply_template', requireAuth, (_req, res) => {
  res.json({ detail: 'Template applied' })
})

// Create single user
panelRouter.post('/api/user', requireAuth, (req: AuthedRequest, res) => {
  const b = req.body || {}
  const username = String(b.username || '').trim()
  if (!username) {
    res.status(422).json({ detail: 'Username is required' })
    return
  }
  if (store.users.some(u => u.username.toLowerCase() === username.toLowerCase())) {
    res.status(409).json({ detail: 'User already exists' })
    return
  }
  const token = `sub_${crypto.randomBytes(6).toString('hex')}`
  const user: StoredUser = {
    id: nextId(store.users),
    username,
    status: b.status || 'active',
    used_traffic: 0,
    lifetime_used_traffic: 0,
    data_limit: b.data_limit != null ? Number(b.data_limit) : null,
    data_limit_reset_strategy: b.data_limit_reset_strategy || 'no_reset',
    expire: b.expire ?? null,
    on_hold_expire_duration: b.on_hold_expire_duration ?? null,
    on_hold_timeout: b.on_hold_timeout ?? null,
    group_ids: Array.isArray(b.group_ids) ? b.group_ids.map(Number) : [1],
    auto_delete_in_days: b.auto_delete_in_days ?? null,
    hwid_limit: b.hwid_limit ?? null,
    note: b.note ?? null,
    next_plan: b.next_plan ?? null,
    proxy_settings: b.proxy_settings || { vless: { id: crypto.randomUUID(), flow: 'xtls-rprx-vision' } },
    created_at: new Date().toISOString(),
    edit_at: null,
    online_at: null,
    sub_updated_at: null,
    sub_last_user_agent: null,
    subscription_token: token,
    subscription_url: `/sub/${token}`,
    admin_id: req.admin!.id,
  }
  store.users.push(user)
  res.status(201).json(buildUserResponse(user))
})

function modifyUserRecord(user: StoredUser, b: Record<string, unknown>) {
  if (b.status !== undefined) user.status = b.status as StoredUser['status']
  if (b.data_limit !== undefined) user.data_limit = b.data_limit !== null ? Number(b.data_limit) : null
  if (b.data_limit_reset_strategy !== undefined) user.data_limit_reset_strategy = b.data_limit_reset_strategy as StoredUser['data_limit_reset_strategy']
  if (b.expire !== undefined) user.expire = b.expire as string | number | null
  if (b.on_hold_expire_duration !== undefined) user.on_hold_expire_duration = b.on_hold_expire_duration as number | null
  if (b.on_hold_timeout !== undefined) user.on_hold_timeout = b.on_hold_timeout as string | number | null
  if (Array.isArray(b.group_ids)) user.group_ids = b.group_ids.map(Number)
  if (b.auto_delete_in_days !== undefined) user.auto_delete_in_days = b.auto_delete_in_days as number | null
  if (b.hwid_limit !== undefined) user.hwid_limit = b.hwid_limit !== null ? Number(b.hwid_limit) : null
  if (b.note !== undefined) user.note = b.note as string | null
  if (b.next_plan !== undefined) user.next_plan = b.next_plan as Record<string, unknown> | null
  if (b.proxy_settings !== undefined) user.proxy_settings = b.proxy_settings as Record<string, unknown>
  user.edit_at = new Date().toISOString()
  return buildUserResponse(user)
}

for (const prefix of ['/api/user/by-id/:ident', '/api/user/by-username/:ident', '/api/user/:ident']) {
  panelRouter.get(prefix, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    res.json(buildUserResponse(user))
  })

  panelRouter.put(prefix, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    res.json(modifyUserRecord(user, req.body || {}))
  })

  panelRouter.delete(prefix, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    store.users = store.users.filter(u => u.id !== user.id)
    res.json({ detail: 'User deleted' })
  })

  panelRouter.put(`${prefix}/disabled`, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    user.status = req.body?.disabled === false ? 'active' : 'disabled'
    res.json(buildUserResponse(user))
  })

  panelRouter.post(`${prefix}/reset`, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    user.used_traffic = 0
    if (user.status === 'limited') user.status = 'active'
    res.json(buildUserResponse(user))
  })

  panelRouter.post(`${prefix}/revoke_sub`, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    const token = `sub_${crypto.randomBytes(6).toString('hex')}`
    user.subscription_token = token
    user.subscription_url = `/sub/${token}`
    res.json(buildUserResponse(user))
  })

  panelRouter.put(`${prefix}/set_owner`, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    const owner = store.admins.find(a => a.username === req.query.admin_username || a.username === req.body?.admin_username)
    if (owner) user.admin_id = owner.id
    res.json(buildUserResponse(user))
  })

  panelRouter.post(`${prefix}/active_next`, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    if (user.next_plan) {
      user.next_plan = null
      user.used_traffic = 0
      user.status = 'active'
    }
    res.json(buildUserResponse(user))
  })

  panelRouter.get(`${prefix}/usage`, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    const period = String(req.query.period || 'day')
    const { start, end, buckets } = generateTimeBuckets(req.query.start as string | undefined, req.query.end as string | undefined, period)
    res.json({
      period,
      start,
      end,
      stats: {
        all: buckets.map((b, i) => ({
          period_start: b,
          total_traffic: Math.round((user.used_traffic / Math.max(1, buckets.length)) * (0.8 + (i % 3) * 0.2)),
        })),
      },
    })
  })

  panelRouter.get(`${prefix}/sub_update`, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    const updates = user.sub_updated_at
      ? [{ created_at: user.sub_updated_at, user_agent: user.sub_last_user_agent || 'v2rayNG/1.9.32', ip: '203.0.113.45' }]
      : []
    res.json({ updates, count: updates.length })
  })
}

for (const prefix of ['/api/user/from_template/by-id/:ident', '/api/user/from_template/by-username/:ident', '/api/user/from_template/:ident']) {
  panelRouter.put(prefix, requireAuth, (req, res) => {
    const user = findUser(req.params.ident)
    if (!user) {
      res.status(404).json({ detail: 'User not found' })
      return
    }
    res.json(modifyUserRecord(user, req.body || {}))
  })
}

panelRouter.get('/api/user/:userId/subscription/:clientType', requireAuth, (req, res) => {
  const user = findUser(req.params.userId)
  if (!user) {
    res.status(404).json({ detail: 'User not found' })
    return
  }
  const links = buildSubscriptionLinks(user)
  if (req.params.clientType === 'sing-box') {
    res.json({ outbounds: [{ type: 'vless', tag: 'proxy' }] })
    return
  }
  res.type('text/plain').send(links.join('\n'))
})

// HWID endpoints
panelRouter.get('/api/user/:userId/hwids', requireAuth, (req, res) => {
  const list = store.userHwids.get(Number(req.params.userId)) ?? []
  res.json({ hwids: list, total: list.length })
})

panelRouter.post('/api/user/:userId/hwids/reset', requireAuth, (req, res) => {
  store.userHwids.set(Number(req.params.userId), [])
  res.json({ detail: 'HWIDs cleared' })
})

panelRouter.delete('/api/user/:userId/hwids/:hwid', requireAuth, (req, res) => {
  const uid = Number(req.params.userId)
  const list = store.userHwids.get(uid) ?? []
  store.userHwids.set(uid, list.filter(h => h.hwid !== req.params.hwid))
  res.json({ detail: 'HWID removed' })
})

// ---------- Public Subscription Routes ----------
function findUserBySubToken(token: string) {
  return store.users.find(u => u.subscription_token === token || u.username === token) ?? store.users[0]
}

panelRouter.get('/sub/:token/info', (req, res) => {
  const user = findUserBySubToken(req.params.token)
  res.json(buildUserResponse(user))
})

panelRouter.get('/sub/:token/raw', (req, res) => {
  const user = findUserBySubToken(req.params.token)
  res.json({ links: buildSubscriptionLinks(user) })
})

panelRouter.get('/sub/:token/apps', (_req, res) => {
  res.json([
    { name: 'v2rayNG', platform: 'Android', icon_url: '', import_url: 'v2rayng://install-config?url={url}' },
    { name: 'Streisand', platform: 'iOS', icon_url: '', import_url: 'streisand://import/{url}' },
    { name: 'Hiddify', platform: 'Desktop', icon_url: '', import_url: 'hiddify://import/{url}' },
  ])
})

panelRouter.get('/sub/:token/usage', (req, res) => {
  const user = findUserBySubToken(req.params.token)
  res.json({ used_traffic: user.used_traffic, data_limit: user.data_limit, stats: { all: [] } })
})

panelRouter.get('/sub/:token/dns', (req, res) => {
  const user = findUserBySubToken(req.params.token)
  const acc = store.dnsAccounts.find(a => a.user_id === user.id) ?? store.dnsAccounts[0]
  res.json({
    account: acc,
    doh_url: `/dns-query/${acc?.token || 'dns_tok_demo'}`,
    leases: acc ? store.dnsLeases.filter(l => l.account_id === acc.id) : [],
    routes: store.smartDnsRoutes,
  })
})

panelRouter.get('/sub/:token/:clientType', (req, res) => {
  const user = findUserBySubToken(req.params.token)
  const links = buildSubscriptionLinks(user)
  res.type('text/plain').send(links.join('\n'))
})

panelRouter.get('/sub/:token', (req, res) => {
  const user = findUserBySubToken(req.params.token)
  const links = buildSubscriptionLinks(user)
  res.type('text/plain').send(Buffer.from(links.join('\n')).toString('base64'))
})

panelRouter.head('/sub/:token', (req, res) => {
  const user = findUserBySubToken(req.params.token)
  res.setHeader('subscription-userinfo', `upload=0; download=${user.used_traffic}; total=${user.data_limit || 0}`)
  res.status(200).end()
})

// ---------- Nodes ----------
panelRouter.get('/api/nodes', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 50)
  const name = String(req.query.name || '').toLowerCase()
  let nodes = [...store.nodes].sort((a, b) => a.sort_order - b.sort_order)
  if (name) nodes = nodes.filter(n => n.name.toLowerCase().includes(name))
  res.json({ nodes: nodes.slice(offset, offset + limit), total: nodes.length })
})

panelRouter.get('/api/nodes/simple', requireAuth, (_req, res) => {
  const nodes = [...store.nodes].sort((a, b) => a.sort_order - b.sort_order).map(n => ({ id: n.id, name: n.name, status: n.status }))
  res.json({ nodes, total: nodes.length })
})

panelRouter.get('/api/node/settings', requireAuth, (_req, res) => {
  res.json({
    min_node_version: 'v1.0.0',
    certificate: '-----BEGIN CERTIFICATE-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A\n-----END CERTIFICATE-----',
  })
})

panelRouter.get('/api/node/usage', requireAuth, (req, res) => {
  const period = String(req.query.period || 'hour')
  const { start, end, buckets } = generateTimeBuckets(req.query.start as string | undefined, req.query.end as string | undefined, period)
  const stats: Record<string, Array<{ period_start: string; uplink: number; downlink: number }>> = {}
  for (const n of store.nodes) {
    stats[n.name] = buckets.map((b, i) => ({
      period_start: b,
      uplink: Math.round((n.uplink / Math.max(1, buckets.length)) * (0.7 + (i % 3) * 0.2)),
      downlink: Math.round((n.downlink / Math.max(1, buckets.length)) * (0.75 + (i % 4) * 0.15)),
    }))
  }
  res.json({ period, start, end, stats })
})

panelRouter.get('/api/node/usage/outbounds', requireAuth, (req, res) => {
  const period = String(req.query.period || 'hour')
  const { start, end, buckets } = generateTimeBuckets(req.query.start as string | undefined, req.query.end as string | undefined, period)
  res.json({
    period,
    start,
    end,
    stats: {
      direct: buckets.map((b, i) => ({
        period_start: b,
        uplink: (120 + (i % 5) * 30) * 1024 ** 2,
        downlink: (380 + (i % 4) * 55) * 1024 ** 2,
      })),
    },
  })
})

panelRouter.get('/api/node/user_counts/:metric', requireAuth, (req, res) => {
  const period = String(req.query.period || 'day')
  const metric = req.params.metric
  const { start, end, buckets } = generateTimeBuckets(req.query.start as string | undefined, req.query.end as string | undefined, period)
  const stats: Record<string, Array<{ period_start: string; count: number }>> = {}
  for (const n of store.nodes) {
    stats[n.name] = buckets.map((b, i) => ({ period_start: b, count: Math.max(1, 3 + (i % 4)) }))
  }
  res.json({ period, start, end, metric, stats })
})

panelRouter.get('/api/nodes/realtime_stats', requireAuth, (_req, res) => {
  const result: Record<string, unknown> = {}
  for (const n of store.nodes) {
    result[String(n.id)] = {
      mem_total: 8 * 1024 ** 3,
      mem_used: (2.6 + (n.id % 3) * 0.7) * 1024 ** 3,
      cpu_cores: 4,
      cpu_usage: 18 + n.id * 7,
      incoming_bandwidth_speed: (12 + n.id * 4) * 1024 ** 2,
      outgoing_bandwidth_speed: (34 + n.id * 9) * 1024 ** 2,
      uptime: 432000 + n.id * 18000,
    }
  }
  res.json(result)
})

panelRouter.post('/api/nodes/order', requireAuth, (req, res) => {
  const ids: number[] = Array.isArray(req.body?.node_ids) ? req.body.node_ids.map(Number) : []
  ids.forEach((id, idx) => {
    const n = store.nodes.find(x => x.id === id)
    if (n) n.sort_order = idx
  })
  res.json({ detail: 'Reordered' })
})

panelRouter.post('/api/nodes/reconnect', requireAuth, (_req, res) => {
  for (const n of store.nodes) {
    if (n.status !== 'disabled') n.status = 'connected'
  }
  res.json({ detail: 'All nodes reconnected' })
})

panelRouter.delete('/api/nodes/clear_usage_data/:table', requireAuth, (_req, res) => {
  res.json({ detail: 'Usage data cleared' })
})

panelRouter.post('/api/nodes/bulk/delete', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.node_ids || []).map(Number))
  store.nodes = store.nodes.filter(n => !ids.has(n.id))
  res.json({ detail: 'Nodes deleted' })
})

panelRouter.post('/api/nodes/bulk/disable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.node_ids || []).map(Number))
  for (const n of store.nodes) if (ids.has(n.id)) n.status = 'disabled'
  res.json({ detail: 'Nodes disabled' })
})

panelRouter.post('/api/nodes/bulk/enable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.node_ids || []).map(Number))
  for (const n of store.nodes) if (ids.has(n.id)) n.status = 'connected'
  res.json({ detail: 'Nodes enabled' })
})

panelRouter.post('/api/nodes/bulk/reset', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.node_ids || []).map(Number))
  for (const n of store.nodes) {
    if (ids.has(n.id)) {
      n.uplink = 0
      n.downlink = 0
    }
  }
  res.json({ detail: 'Nodes reset' })
})

panelRouter.post('/api/nodes/bulk/reconnect', requireAuth, (_req, res) => {
  res.json({ detail: 'Nodes reconnected' })
})

panelRouter.post('/api/nodes/bulk/update', requireAuth, (_req, res) => {
  res.json({ detail: 'Nodes updated' })
})

panelRouter.post('/api/node', requireAuth, (req, res) => {
  const b = req.body || {}
  const node: StoredNode = {
    id: nextId(store.nodes),
    name: String(b.name || `Node ${store.nodes.length + 1}`),
    address: String(b.address || '127.0.0.1'),
    port: Number(b.port || 62050),
    api_port: Number(b.api_port || 62051),
    tunnel_api_port: Number(b.tunnel_api_port || 62052),
    health_echo_port: Number(b.health_echo_port || 62053),
    usage_coefficient: Number(b.usage_coefficient || 1),
    connection_type: b.connection_type || 'grpc',
    server_ca: String(b.server_ca || ''),
    keep_alive: Number(b.keep_alive || 60),
    core_config_id: b.core_config_id ? Number(b.core_config_id) : 1,
    api_key: b.api_key || crypto.randomUUID(),
    data_limit: Number(b.data_limit || 0),
    data_limit_reset_strategy: b.data_limit_reset_strategy || 'no_reset',
    reset_time: Number(b.reset_time ?? -1),
    default_timeout: Number(b.default_timeout || 10),
    internal_timeout: Number(b.internal_timeout || 15),
    proxy_url: b.proxy_url ?? null,
    sort_order: store.nodes.length,
    xray_version: '25.8.3',
    node_version: '1.2.4',
    status: b.status || 'connected',
    message: 'Node connected and synchronized',
    uplink: 0,
    downlink: 0,
    lifetime_uplink: 0,
    lifetime_downlink: 0,
    core_version: '25.8.3',
  }
  store.nodes.push(node)
  res.status(201).json(node)
})

panelRouter.get('/api/node/:nodeId', requireAuth, (req, res) => {
  const node = store.nodes.find(n => n.id === Number(req.params.nodeId))
  if (!node) {
    res.status(404).json({ detail: 'Node not found' })
    return
  }
  res.json(node)
})

panelRouter.put('/api/node/:nodeId', requireAuth, (req, res) => {
  const node = store.nodes.find(n => n.id === Number(req.params.nodeId))
  if (!node) {
    res.status(404).json({ detail: 'Node not found' })
    return
  }
  Object.assign(node, req.body || {})
  res.json(node)
})

panelRouter.delete('/api/node/:nodeId', requireAuth, (req, res) => {
  const id = Number(req.params.nodeId)
  store.nodes = store.nodes.filter(n => n.id !== id)
  res.json({ detail: 'Node deleted' })
})

for (const action of ['update', 'core_update', 'geofiles', 'reset', 'reconnect', 'sync']) {
  panelRouter.post(`/api/node/:nodeId/${action}`, requireAuth, (req, res) => {
    const node = store.nodes.find(n => n.id === Number(req.params.nodeId))
    if (!node) {
      res.status(404).json({ detail: 'Node not found' })
      return
    }
    if (action === 'reset') {
      node.uplink = 0
      node.downlink = 0
    } else if (action === 'reconnect') {
      node.status = 'connected'
    }
    res.json(node)
  })
}

panelRouter.get('/api/node/:nodeId/realtime_stats', requireAuth, (req, res) => {
  const n = store.nodes.find(x => x.id === Number(req.params.nodeId))
  if (!n) {
    res.status(404).json({ detail: 'Node not found' })
    return
  }
  res.json({
    mem_total: 8 * 1024 ** 3,
    mem_used: (2.6 + (n.id % 3) * 0.7) * 1024 ** 3,
    cpu_cores: 4,
    cpu_usage: 18 + n.id * 7,
    incoming_bandwidth_speed: (12 + n.id * 4) * 1024 ** 2,
    outgoing_bandwidth_speed: (34 + n.id * 9) * 1024 ** 2,
    uptime: 432000 + n.id * 18000,
  })
})

panelRouter.get('/api/node/:nodeId/stats', requireAuth, (req, res) => {
  const period = String(req.query.period || 'hour')
  const { start, end, buckets } = generateTimeBuckets(req.query.start as string | undefined, req.query.end as string | undefined, period)
  res.json({
    period,
    start,
    end,
    stats: buckets.map((b, i) => ({
      period_start: b,
      mem_usage_percentage: 36 + (i % 7) * 2.1,
      cpu_usage_percentage: 22 + (i % 5) * 4.5,
      incoming_bandwidth_speed: (10 + (i % 6) * 3) * 1024 ** 2,
      outgoing_bandwidth_speed: (28 + (i % 5) * 6) * 1024 ** 2,
    })),
  })
})

panelRouter.get('/api/node/:nodeId/outbounds_latency', requireAuth, (_req, res) => {
  res.json({
    latencies: [
      { outbound_tag: 'direct', latency_ms: 14 },
      { outbound_tag: 'cloudflare', latency_ms: 21 },
    ],
  })
})

panelRouter.get('/api/node/online_stats/:userId/ip', requireAuth, (_req, res) => {
  res.json({ ips: [{ ip: '203.0.113.45', node_id: 1, last_seen: new Date().toISOString() }] })
})

panelRouter.get('/api/node/:nodeId/online_stats/:userId', requireAuth, (_req, res) => {
  res.json({ online: true, last_seen: new Date().toISOString() })
})

panelRouter.get('/api/node/:nodeId/online_stats/:userId/ip', requireAuth, (req, res) => {
  res.json({ ips: [{ ip: '203.0.113.45', node_id: Number(req.params.nodeId), last_seen: new Date().toISOString() }] })
})

// SSE Node Logs
panelRouter.get('/api/node/:nodeId/logs', (req, res) => {
  const node = store.nodes.find(n => n.id === Number(req.params.nodeId))
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  })
  const nodeName = node?.name || `Node #${req.params.nodeId}`
  const sendLine = (level: string, msg: string) => {
    const ts = new Date().toISOString()
    res.write(`data: ${ts} [${level}] ${nodeName}: ${msg}\n\n`)
  }
  sendLine('info', 'Connected to node log stream')
  sendLine('info', `Core ${node?.core_version || '25.8.3'} synchronized (${store.users.filter(u => u.status === 'active').length} active clients)`)
  const timer = setInterval(() => {
    sendLine('debug', 'Heartbeat OK · telemetry flushed')
  }, 6000)
  req.on('close', () => clearInterval(timer))
})

// ---------- Cores ----------
panelRouter.get('/api/cores', requireAuth, (_req, res) => {
  const cores = [...store.cores].sort((a, b) => a.sort_order - b.sort_order)
  res.json({ count: cores.length, cores })
})

panelRouter.get('/api/cores/simple', requireAuth, (_req, res) => {
  const cores = [...store.cores].sort((a, b) => a.sort_order - b.sort_order).map(c => ({ id: c.id, name: c.name, type: c.type }))
  res.json({ cores, total: cores.length })
})

panelRouter.post('/api/core', requireAuth, (req, res) => {
  const b = req.body || {}
  const core: StoredCore = {
    id: nextId(store.cores),
    name: String(b.name || `Core ${store.cores.length + 1}`),
    type: b.type === 'wg' ? 'wg' : 'xray',
    config: b.config || {},
    exclude_inbound_tags: Array.isArray(b.exclude_inbound_tags) ? b.exclude_inbound_tags : [],
    fallbacks_inbound_tags: Array.isArray(b.fallbacks_inbound_tags) ? b.fallbacks_inbound_tags : [],
    sort_order: store.cores.length,
    created_at: new Date().toISOString(),
  }
  store.cores.push(core)
  res.status(201).json(core)
})

panelRouter.post('/api/core/reality-scan', requireAuth, (req, res) => {
  const target = String(req.body?.target || 'www.cloudflare.com:443')
  res.json({
    target,
    compatible: true,
    tls_version: 'TLSv1.3',
    alpn: 'h2',
    sni: target.split(':')[0],
    server_names: [target.split(':')[0]],
  })
})

panelRouter.post('/api/cores/order', requireAuth, (req, res) => {
  const ids: number[] = Array.isArray(req.body?.core_ids) ? req.body.core_ids.map(Number) : []
  ids.forEach((id, idx) => {
    const c = store.cores.find(x => x.id === id)
    if (c) c.sort_order = idx
  })
  res.json({ detail: 'Reordered' })
})

panelRouter.post('/api/cores/bulk/delete', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.core_ids || []).map(Number))
  store.cores = store.cores.filter(c => !ids.has(c.id))
  res.json({ detail: 'Deleted' })
})

panelRouter.get('/api/core/:coreId', requireAuth, (req, res) => {
  const core = store.cores.find(c => c.id === Number(req.params.coreId))
  if (!core) {
    res.status(404).json({ detail: 'Core not found' })
    return
  }
  res.json(core)
})

panelRouter.put('/api/core/:coreId', requireAuth, (req, res) => {
  const core = store.cores.find(c => c.id === Number(req.params.coreId))
  if (!core) {
    res.status(404).json({ detail: 'Core not found' })
    return
  }
  const b = req.body || {}
  if (b.name !== undefined) core.name = String(b.name)
  if (b.config !== undefined) core.config = b.config
  if (Array.isArray(b.exclude_inbound_tags)) core.exclude_inbound_tags = b.exclude_inbound_tags
  if (Array.isArray(b.fallbacks_inbound_tags)) core.fallbacks_inbound_tags = b.fallbacks_inbound_tags
  res.json(core)
})

panelRouter.delete('/api/core/:coreId', requireAuth, (req, res) => {
  store.cores = store.cores.filter(c => c.id !== Number(req.params.coreId))
  res.json({ detail: 'Core deleted' })
})

panelRouter.post('/api/core/:coreId/restart', requireAuth, (req, res) => {
  const core = store.cores.find(c => c.id === Number(req.params.coreId))
  if (!core) {
    res.status(404).json({ detail: 'Core not found' })
    return
  }
  res.json({ detail: 'Core restarted', core_id: core.id })
})

// ---------- Groups ----------
panelRouter.get('/api/groups', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 50)
  const name = String(req.query.name || '').toLowerCase()
  let groups = store.groups
  if (name) groups = groups.filter(g => g.name.toLowerCase().includes(name))
  res.json({ groups: groups.slice(offset, offset + limit), total: groups.length })
})

panelRouter.get('/api/groups/simple', requireAuth, (_req, res) => {
  res.json({
    groups: store.groups.map(g => ({ id: g.id, name: g.name })),
    total: store.groups.length,
  })
})

panelRouter.post('/api/group', requireAuth, (req, res) => {
  const b = req.body || {}
  const group: StoredGroup = {
    id: nextId(store.groups),
    name: String(b.name || `Group ${store.groups.length + 1}`),
    inbound_tags: Array.isArray(b.inbound_tags) ? b.inbound_tags : [],
    is_disabled: Boolean(b.is_disabled),
  }
  store.groups.push(group)
  res.status(201).json(group)
})

panelRouter.get('/api/group/:groupId', requireAuth, (req, res) => {
  const g = store.groups.find(x => x.id === Number(req.params.groupId))
  if (!g) {
    res.status(404).json({ detail: 'Group not found' })
    return
  }
  res.json(g)
})

panelRouter.put('/api/group/:groupId', requireAuth, (req, res) => {
  const g = store.groups.find(x => x.id === Number(req.params.groupId))
  if (!g) {
    res.status(404).json({ detail: 'Group not found' })
    return
  }
  Object.assign(g, req.body || {})
  res.json(g)
})

panelRouter.delete('/api/group/:groupId', requireAuth, (req, res) => {
  store.groups = store.groups.filter(x => x.id !== Number(req.params.groupId))
  res.json({ detail: 'Group deleted' })
})

panelRouter.post('/api/groups/bulk/add', requireAuth, (req, res) => {
  const groupIds: number[] = (req.body?.group_ids || []).map(Number)
  for (const u of store.users) {
    u.group_ids = Array.from(new Set([...u.group_ids, ...groupIds]))
  }
  res.json({ detail: 'Groups added' })
})

panelRouter.post('/api/groups/bulk/remove', requireAuth, (req, res) => {
  const groupIds = new Set<number>((req.body?.group_ids || []).map(Number))
  for (const u of store.users) {
    u.group_ids = u.group_ids.filter(g => !groupIds.has(g))
  }
  res.json({ detail: 'Groups removed' })
})

panelRouter.post('/api/groups/bulk/delete', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.group_ids || []).map(Number))
  store.groups = store.groups.filter(g => !ids.has(g.id))
  res.json({ detail: 'Groups deleted' })
})

panelRouter.post('/api/groups/bulk/disable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.group_ids || []).map(Number))
  for (const g of store.groups) if (ids.has(g.id)) g.is_disabled = true
  res.json({ detail: 'Groups disabled' })
})

panelRouter.post('/api/groups/bulk/enable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.group_ids || []).map(Number))
  for (const g of store.groups) if (ids.has(g.id)) g.is_disabled = false
  res.json({ detail: 'Groups enabled' })
})

// ---------- Hosts ----------
panelRouter.get('/api/hosts', requireAuth, (req, res) => {
  const tag = req.query.inbound_tag ? String(req.query.inbound_tag) : null
  const hosts = tag ? store.hosts.filter(h => h.inbound_tag === tag) : store.hosts
  res.json(hosts)
})

panelRouter.put('/api/hosts', requireAuth, (req, res) => {
  if (Array.isArray(req.body)) {
    store.hosts = req.body as StoredHost[]
  }
  res.json(store.hosts)
})

panelRouter.post('/api/host', requireAuth, (req, res) => {
  const b = req.body || {}
  const host: StoredHost = {
    id: nextId(store.hosts),
    native_tunnel_id: b.native_tunnel_id ?? null,
    remark: String(b.remark || 'New Host'),
    address: Array.isArray(b.address) ? b.address : [String(b.address || 'example.net')],
    inbound_tag: b.inbound_tag ?? null,
    port: b.port !== undefined && b.port !== null ? Number(b.port) : null,
    sni: b.sni ?? null,
    host: b.host ?? null,
    path: b.path ?? null,
    security: b.security || 'inbound_default',
    alpn: b.alpn ?? null,
    fingerprint: String(b.fingerprint || 'chrome'),
    allowinsecure: b.allowinsecure ?? false,
    is_disabled: Boolean(b.is_disabled),
    http_headers: b.http_headers ?? null,
    transport_settings: b.transport_settings ?? null,
    mux_settings: b.mux_settings ?? null,
    fragment_settings: b.fragment_settings ?? null,
    noise_settings: b.noise_settings ?? null,
    random_user_agent: Boolean(b.random_user_agent),
    use_sni_as_host: Boolean(b.use_sni_as_host),
    vless_route: b.vless_route ?? null,
    priority: Number(b.priority ?? store.hosts.length + 1),
    status: b.status ?? null,
    ech: b.ech ?? null,
    pinned_peer_cert_sha256: b.pinned_peer_cert_sha256 ?? null,
    verify_peer_cert_by_name: b.verify_peer_cert_by_name ?? null,
    wireguard_overrides: b.wireguard_overrides ?? null,
    subscription_templates: b.subscription_templates ?? null,
    final_mask_settings: b.final_mask_settings ?? null,
    cipher_suites: b.cipher_suites ?? null,
  }
  store.hosts.push(host)
  res.status(201).json(host)
})

panelRouter.get('/api/host/:hostId', requireAuth, (req, res) => {
  const h = store.hosts.find(x => x.id === Number(req.params.hostId))
  if (!h) {
    res.status(404).json({ detail: 'Host not found' })
    return
  }
  res.json(h)
})

panelRouter.put('/api/host/:hostId', requireAuth, (req, res) => {
  const h = store.hosts.find(x => x.id === Number(req.params.hostId))
  if (!h) {
    res.status(404).json({ detail: 'Host not found' })
    return
  }
  Object.assign(h, req.body || {})
  res.json(h)
})

panelRouter.delete('/api/host/:hostId', requireAuth, (req, res) => {
  store.hosts = store.hosts.filter(x => x.id !== Number(req.params.hostId))
  res.json({ detail: 'Host deleted' })
})

panelRouter.post('/api/hosts/bulk/delete', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.host_ids || []).map(Number))
  store.hosts = store.hosts.filter(h => !ids.has(h.id))
  res.json({ detail: 'Hosts deleted' })
})

panelRouter.post('/api/hosts/bulk/disable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.host_ids || []).map(Number))
  for (const h of store.hosts) if (ids.has(h.id)) h.is_disabled = true
  res.json({ detail: 'Hosts disabled' })
})

panelRouter.post('/api/hosts/bulk/enable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.host_ids || []).map(Number))
  for (const h of store.hosts) if (ids.has(h.id)) h.is_disabled = false
  res.json({ detail: 'Hosts enabled' })
})

// ---------- User Templates ----------
panelRouter.get('/api/user_templates', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 50)
  res.json(store.userTemplates.slice(offset, offset + limit))
})

panelRouter.get('/api/user_templates/simple', requireAuth, (_req, res) => {
  res.json({
    templates: store.userTemplates.map(t => ({ id: t.id, name: t.name })),
    total: store.userTemplates.length,
  })
})

panelRouter.post('/api/user_template', requireAuth, (req, res) => {
  const b = req.body || {}
  const tpl: StoredUserTemplate = {
    id: nextId(store.userTemplates),
    name: String(b.name || `Template ${store.userTemplates.length + 1}`),
    data_limit: b.data_limit != null ? Number(b.data_limit) : null,
    hwid_limit: b.hwid_limit != null ? Number(b.hwid_limit) : null,
    expire_duration: b.expire_duration != null ? Number(b.expire_duration) : null,
    username_prefix: b.username_prefix ?? null,
    username_suffix: b.username_suffix ?? null,
    group_ids: Array.isArray(b.group_ids) ? b.group_ids.map(Number) : [],
    extra_settings: b.extra_settings ?? null,
    status: b.status ?? 'active',
    reset_usages: Boolean(b.reset_usages),
    on_hold_timeout: b.on_hold_timeout != null ? Number(b.on_hold_timeout) : null,
    data_limit_reset_strategy: b.data_limit_reset_strategy || 'no_reset',
    is_disabled: Boolean(b.is_disabled),
  }
  store.userTemplates.push(tpl)
  res.status(201).json(tpl)
})

panelRouter.get('/api/user_template/:templateId', requireAuth, (req, res) => {
  const t = store.userTemplates.find(x => x.id === Number(req.params.templateId))
  if (!t) {
    res.status(404).json({ detail: 'User template not found' })
    return
  }
  res.json(t)
})

panelRouter.put('/api/user_template/:templateId', requireAuth, (req, res) => {
  const t = store.userTemplates.find(x => x.id === Number(req.params.templateId))
  if (!t) {
    res.status(404).json({ detail: 'User template not found' })
    return
  }
  Object.assign(t, req.body || {})
  res.json(t)
})

panelRouter.delete('/api/user_template/:templateId', requireAuth, (req, res) => {
  store.userTemplates = store.userTemplates.filter(x => x.id !== Number(req.params.templateId))
  res.json({ detail: 'User template deleted' })
})

panelRouter.post('/api/user_templates/bulk/delete', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.template_ids || []).map(Number))
  store.userTemplates = store.userTemplates.filter(t => !ids.has(t.id))
  res.json({ detail: 'Templates deleted' })
})

panelRouter.post('/api/user_templates/bulk/disable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.template_ids || []).map(Number))
  for (const t of store.userTemplates) if (ids.has(t.id)) t.is_disabled = true
  res.json({ detail: 'Templates disabled' })
})

panelRouter.post('/api/user_templates/bulk/enable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.template_ids || []).map(Number))
  for (const t of store.userTemplates) if (ids.has(t.id)) t.is_disabled = false
  res.json({ detail: 'Templates enabled' })
})

// ---------- Client Templates ----------
panelRouter.get('/api/client_templates', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 50)
  res.json({
    count: store.clientTemplates.length,
    templates: store.clientTemplates.slice(offset, offset + limit),
  })
})

panelRouter.get('/api/client_templates/simple', requireAuth, (_req, res) => {
  res.json({
    templates: store.clientTemplates.map(t => ({
      id: t.id,
      name: t.name,
      template_type: t.template_type,
      is_default: t.is_default,
    })),
    total: store.clientTemplates.length,
  })
})

panelRouter.post('/api/client_template', requireAuth, (req, res) => {
  const b = req.body || {}
  const tpl: StoredClientTemplate = {
    id: nextId(store.clientTemplates),
    name: String(b.name || `Client Template ${store.clientTemplates.length + 1}`),
    template_type: b.template_type || 'sing-box',
    content: String(b.content || '{}'),
    is_default: Boolean(b.is_default),
    is_system: false,
  }
  store.clientTemplates.push(tpl)
  res.status(201).json(tpl)
})

panelRouter.get('/api/client_template/:templateId', requireAuth, (req, res) => {
  const t = store.clientTemplates.find(x => x.id === Number(req.params.templateId))
  if (!t) {
    res.status(404).json({ detail: 'Client template not found' })
    return
  }
  res.json(t)
})

panelRouter.put('/api/client_template/:templateId', requireAuth, (req, res) => {
  const t = store.clientTemplates.find(x => x.id === Number(req.params.templateId))
  if (!t) {
    res.status(404).json({ detail: 'Client template not found' })
    return
  }
  Object.assign(t, req.body || {})
  res.json(t)
})

panelRouter.delete('/api/client_template/:templateId', requireAuth, (req, res) => {
  store.clientTemplates = store.clientTemplates.filter(x => x.id !== Number(req.params.templateId))
  res.json({ detail: 'Client template deleted' })
})

panelRouter.post('/api/client_templates/bulk/delete', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.template_ids || []).map(Number))
  store.clientTemplates = store.clientTemplates.filter(t => !ids.has(t.id))
  res.json({ detail: 'Client templates deleted' })
})
