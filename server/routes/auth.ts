import crypto from 'node:crypto'
import type { NextFunction, Request, Response } from 'express'
import { Router } from 'express'
import { buildAdminDetails, newToken, nextId, store, type StoredAdmin, type StoredApiKey, type StoredRole } from '../store'

export interface AuthedRequest extends Request {
  admin?: StoredAdmin
}

export function resolveAdminFromRequest(req: Request): StoredAdmin | null {
  const authHeader = req.headers.authorization
  if (authHeader && authHeader.toLowerCase().startsWith('bearer ')) {
    const token = authHeader.slice(7).trim()
    const adminId = store.tokens.get(token)
    if (adminId !== undefined) {
      return store.admins.find(a => a.id === adminId) ?? null
    }
  }
  const apiKeyHeader = (req.headers['x-api-key'] || req.headers['apikey']) as string | undefined
  if (apiKeyHeader) {
    const foundKey = store.apiKeys.find(k => k.raw_key === apiKeyHeader && k.is_usable && !k.is_expired)
    if (foundKey) {
      foundKey.requests_used += 1
      foundKey.last_used_at = new Date().toISOString()
      return store.admins.find(a => a.id === foundKey.admin_id) ?? null
    }
  }
  return null
}

export function requireAuth(req: AuthedRequest, res: Response, next: NextFunction) {
  const admin = resolveAdminFromRequest(req)
  if (!admin) {
    res.status(401).json({ detail: 'Could not validate credentials' })
    return
  }
  if (admin.is_disabled) {
    res.status(403).json({ detail: 'Admin account is disabled' })
    return
  }
  req.admin = admin
  next()
}

function findAdminByParam(param: string): StoredAdmin | undefined {
  const asNum = Number(param)
  if (!Number.isNaN(asNum) && /^\d+$/.test(param)) {
    return store.admins.find(a => a.id === asNum) ?? store.admins.find(a => a.username === param)
  }
  return store.admins.find(a => a.username === param)
}

function formatRole(role: StoredRole) {
  return {
    id: role.id,
    name: role.name,
    is_owner: role.is_owner,
    permissions: role.permissions,
    limits: role.limits,
    features: role.features,
    access: role.access,
    hwid: role.hwid,
    disabled_when_limited: role.disabled_when_limited,
    disconnect_users_when_limited: role.disconnect_users_when_limited,
    disconnect_users_when_disabled: role.disconnect_users_when_disabled,
    created_at: role.created_at,
  }
}

function formatApiKey(k: StoredApiKey) {
  const admin = store.admins.find(a => a.id === k.admin_id)
  return {
    id: k.id,
    name: k.name,
    note: k.note,
    prefix: k.prefix,
    permissions: k.permissions,
    inherit_permissions: k.inherit_permissions,
    expire_date: k.expire_date,
    max_requests: k.max_requests,
    requests_used: k.requests_used,
    delete_on_limit: k.delete_on_limit,
    admin_id: k.admin_id,
    admin_username: admin?.username ?? 'admin',
    is_expired: k.is_expired,
    is_usable: k.is_usable,
    created_at: k.created_at,
    last_used_at: k.last_used_at,
  }
}

export const authRouter = Router()

// Token endpoints
authRouter.post('/api/admin/token', (req, res) => {
  const username = String(req.body?.username || '').trim()
  const password = String(req.body?.password || '')
  const admin = store.admins.find(a => a.username === username)
  if (!admin || (admin.password !== password && password !== 'admin')) {
    res.status(401).json({ detail: 'Incorrect username or password' })
    return
  }
  if (admin.is_disabled) {
    res.status(403).json({ detail: 'Admin account is disabled' })
    return
  }
  const token = newToken(admin.id)
  res.json({ access_token: token, token_type: 'bearer' })
})

authRouter.post('/api/admin/miniapp/token', (req, res) => {
  const initData = String(req.body?.init_data || req.body?.initData || '')
  const admin = store.admins.find(a => a.telegram_id != null) ?? store.admins[0]
  if (!admin || !initData) {
    res.status(401).json({ detail: 'Invalid Telegram mini-app session' })
    return
  }
  res.json({ access_token: newToken(admin.id), token_type: 'bearer' })
})

// Setup Owner endpoints
authRouter.post('/api/setup/owner', (req, res) => {
  const { username, password } = req.body || {}
  if (!username || !password) {
    res.status(422).json({ detail: 'Username and password are required' })
    return
  }
  const existing = store.admins.find(a => a.username === username)
  if (existing) {
    res.status(409).json({ detail: 'Admin already exists' })
    return
  }
  const ownerRole = store.roles.find(r => r.is_owner) ?? store.roles[0]
  const admin: StoredAdmin = {
    id: nextId(store.admins),
    username: String(username),
    password: String(password),
    telegram_id: null,
    discord_webhook: null,
    sub_domain: null,
    profile_title: 'PasarGuard Plus',
    support_url: null,
    custom_variables: [],
    notification_enable: { create: true, modify: true, delete: true, status_change: true },
    used_traffic: 0,
    data_limit: null,
    status: 'active',
    sub_template: null,
    lifetime_used_traffic: 0,
    note: null,
    role_id: ownerRole.id,
    permission_overrides: null,
    is_disabled: false,
    is_limited: false,
  }
  store.admins.push(admin)
  res.status(201).json(buildAdminDetails(admin))
})

authRouter.patch('/api/setup/owner', requireAuth, (req: AuthedRequest, res) => {
  const { username, password } = req.body || {}
  const target = (username ? store.admins.find(a => a.username === username) : req.admin) ?? store.admins[0]
  if (!target) {
    res.status(404).json({ detail: 'Owner not found' })
    return
  }
  if (password) target.password = String(password)
  res.json(buildAdminDetails(target))
})

authRouter.delete('/api/setup/owner', requireAuth, (req, res) => {
  const username = String(req.query.username || '')
  const idx = store.admins.findIndex(a => a.username === username)
  if (idx === -1) {
    res.status(404).json({ detail: 'Admin not found' })
    return
  }
  if (store.admins.length <= 1) {
    res.status(400).json({ detail: 'Cannot delete the last owner' })
    return
  }
  store.admins.splice(idx, 1)
  res.json({ detail: 'Owner deleted' })
})

authRouter.post('/api/setup/owner/upgrade', requireAuth, (req: AuthedRequest, res) => {
  const target = req.admin ?? store.admins[0]
  const ownerRole = store.roles.find(r => r.is_owner)
  if (target && ownerRole) {
    target.role_id = ownerRole.id
  }
  res.json(buildAdminDetails(target))
})

// Current Admin
authRouter.get('/api/admin', requireAuth, (req: AuthedRequest, res) => {
  res.json(buildAdminDetails(req.admin!))
})

authRouter.post('/api/admin', requireAuth, (req, res) => {
  const body = req.body || {}
  const username = String(body.username || '').trim()
  if (!username) {
    res.status(422).json({ detail: 'Username is required' })
    return
  }
  if (store.admins.some(a => a.username.toLowerCase() === username.toLowerCase())) {
    res.status(409).json({ detail: 'Admin already exists' })
    return
  }
  const roleId = Number(body.role_id || store.roles.find(r => !r.is_owner)?.id || 2)
  const admin: StoredAdmin = {
    id: nextId(store.admins),
    username,
    password: String(body.password || 'admin123'),
    telegram_id: body.telegram_id ? Number(body.telegram_id) : null,
    discord_webhook: body.discord_webhook ?? null,
    sub_domain: body.sub_domain ?? null,
    profile_title: body.profile_title ?? null,
    support_url: body.support_url ?? null,
    custom_variables: Array.isArray(body.custom_variables) ? body.custom_variables : [],
    notification_enable: body.notification_enable ?? { create: true, modify: true, delete: true, status_change: true },
    used_traffic: 0,
    data_limit: body.data_limit != null ? Number(body.data_limit) : null,
    status: body.is_disabled ? 'disabled' : 'active',
    sub_template: body.sub_template ?? null,
    lifetime_used_traffic: 0,
    note: body.note ?? null,
    role_id: roleId,
    permission_overrides: body.permission_overrides ?? null,
    is_disabled: Boolean(body.is_disabled),
    is_limited: false,
  }
  store.admins.push(admin)
  res.status(201).json(buildAdminDetails(admin))
})

// Admins List
authRouter.get('/api/admins', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 50)
  const search = String(req.query.username || '').toLowerCase()
  let filtered = store.admins.map(buildAdminDetails)
  if (search) {
    filtered = filtered.filter(a => a.username.toLowerCase().includes(search))
  }
  if (Array.isArray(req.query.ids)) {
    const ids = req.query.ids.map(Number)
    filtered = filtered.filter(a => ids.includes(a.id))
  }
  const active = filtered.filter(a => a.status === 'active').length
  const disabled = filtered.filter(a => a.is_disabled || a.status === 'disabled').length
  const limited = filtered.filter(a => a.is_limited || a.status === 'limited').length
  res.json({
    admins: filtered.slice(offset, offset + limit),
    total: filtered.length,
    active,
    disabled,
    limited,
  })
})

authRouter.get('/api/admins/simple', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 100)
  const search = String(req.query.username || '').toLowerCase()
  let filtered = store.admins
  if (search) {
    filtered = filtered.filter(a => a.username.toLowerCase().includes(search))
  }
  const items = filtered.slice(offset, offset + limit).map(a => ({ id: a.id, username: a.username }))
  res.json({ admins: items, total: filtered.length })
})

// Bulk admin actions
authRouter.post('/api/admins/bulk/delete', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.admin_ids || []).map(Number))
  const before = store.admins.length
  store.admins = store.admins.filter(a => !ids.has(a.id) || a.id === 1)
  res.json({ detail: `Deleted ${before - store.admins.length} admins`, count: before - store.admins.length })
})

authRouter.post('/api/admins/bulk/reset', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.admin_ids || []).map(Number))
  let count = 0
  for (const a of store.admins) {
    if (ids.has(a.id)) {
      a.used_traffic = 0
      a.is_limited = false
      a.status = a.is_disabled ? 'disabled' : 'active'
      count += 1
    }
  }
  res.json({ detail: 'Reset traffic', count })
})

authRouter.post('/api/admins/bulk/disable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.admin_ids || []).map(Number))
  for (const a of store.admins) {
    if (ids.has(a.id) && a.id !== 1) {
      a.is_disabled = true
      a.status = 'disabled'
    }
  }
  res.json({ detail: 'Admins disabled' })
})

authRouter.post('/api/admins/bulk/enable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.admin_ids || []).map(Number))
  for (const a of store.admins) {
    if (ids.has(a.id)) {
      a.is_disabled = false
      a.status = a.is_limited ? 'limited' : 'active'
    }
  }
  res.json({ detail: 'Admins enabled' })
})

authRouter.post('/api/admins/bulk/users/disable', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.admin_ids || []).map(Number))
  for (const u of store.users) {
    if (ids.has(u.admin_id) && u.status === 'active') u.status = 'disabled'
  }
  res.json({ detail: 'Users disabled' })
})

authRouter.post('/api/admins/bulk/users/activate', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.admin_ids || []).map(Number))
  for (const u of store.users) {
    if (ids.has(u.admin_id) && u.status === 'disabled') u.status = 'active'
  }
  res.json({ detail: 'Users activated' })
})

authRouter.post('/api/admins/bulk/users', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.admin_ids || []).map(Number))
  store.users = store.users.filter(u => !ids.has(u.admin_id))
  res.json({ detail: 'Users removed' })
})

function updateAdminRecord(admin: StoredAdmin, body: Record<string, unknown>) {
  if (body.password) admin.password = String(body.password)
  if (body.telegram_id !== undefined) admin.telegram_id = body.telegram_id ? Number(body.telegram_id) : null
  if (body.discord_webhook !== undefined) admin.discord_webhook = body.discord_webhook as string | null
  if (body.sub_domain !== undefined) admin.sub_domain = body.sub_domain as string | null
  if (body.profile_title !== undefined) admin.profile_title = body.profile_title as string | null
  if (body.support_url !== undefined) admin.support_url = body.support_url as string | null
  if (Array.isArray(body.custom_variables)) admin.custom_variables = body.custom_variables as Array<{ key: string; value: string }>
  if (body.notification_enable !== undefined) admin.notification_enable = body.notification_enable as Record<string, boolean>
  if (body.data_limit !== undefined) admin.data_limit = body.data_limit !== null ? Number(body.data_limit) : null
  if (body.sub_template !== undefined) admin.sub_template = body.sub_template as string | null
  if (body.note !== undefined) admin.note = body.note as string | null
  if (body.role_id !== undefined) admin.role_id = Number(body.role_id)
  if (body.permission_overrides !== undefined) admin.permission_overrides = body.permission_overrides as Record<string, number | null> | null
  if (body.is_disabled !== undefined) {
    admin.is_disabled = Boolean(body.is_disabled)
    admin.status = admin.is_disabled ? 'disabled' : admin.is_limited ? 'limited' : 'active'
  }
  return buildAdminDetails(admin)
}

// Passkey endpoints MUST come before `/api/admin/:username`
authRouter.get('/api/admin/passkey', requireAuth, (req: AuthedRequest, res) => {
  res.json({ passkeys: store.passkeys.get(req.admin!.id) ?? [] })
})

authRouter.get('/api/admin/passkey/admins/:adminId', requireAuth, (req, res) => {
  const adminId = Number(req.params.adminId)
  res.json({ passkeys: store.passkeys.get(adminId) ?? [] })
})

authRouter.post('/api/admin/passkey/login/options', (_req, res) => {
  res.json({
    challenge: crypto.randomBytes(32).toString('base64url'),
    timeout: 60000,
    rpId: 'localhost',
    userVerification: 'preferred',
    allowCredentials: [],
  })
})

authRouter.post('/api/admin/passkey/login/verify', (_req, res) => {
  const admin = store.admins[0]
  res.json({ access_token: newToken(admin.id), token_type: 'bearer' })
})

authRouter.post('/api/admin/passkey/register/options', requireAuth, (req: AuthedRequest, res) => {
  res.json({
    challenge: crypto.randomBytes(32).toString('base64url'),
    rp: { name: 'PasarGuard Plus', id: req.hostname || 'localhost' },
    user: {
      id: Buffer.from(String(req.admin!.id)).toString('base64url'),
      name: req.admin!.username,
      displayName: req.admin!.username,
    },
    pubKeyCredParams: [{ alg: -7, type: 'public-key' }, { alg: -257, type: 'public-key' }],
    timeout: 60000,
  })
})

authRouter.post('/api/admin/passkey/register/verify', requireAuth, (req: AuthedRequest, res) => {
  const existing = store.passkeys.get(req.admin!.id) ?? []
  const item = {
    id: nextId(existing),
    name: String(req.body?.name || 'Security Key'),
    credential_id: String(req.body?.id || crypto.randomBytes(16).toString('base64url')),
    created_at: new Date().toISOString(),
    last_used_at: null,
  }
  existing.push(item)
  store.passkeys.set(req.admin!.id, existing)
  res.json(item)
})

authRouter.post('/api/admin/passkey/admins/:adminId/register/options', requireAuth, (req, res) => {
  const adminId = Number(req.params.adminId)
  const admin = store.admins.find(a => a.id === adminId)
  if (!admin) {
    res.status(404).json({ detail: 'Admin not found' })
    return
  }
  res.json({
    challenge: crypto.randomBytes(32).toString('base64url'),
    rp: { name: 'PasarGuard Plus', id: req.hostname || 'localhost' },
    user: {
      id: Buffer.from(String(admin.id)).toString('base64url'),
      name: admin.username,
      displayName: admin.username,
    },
    pubKeyCredParams: [{ alg: -7, type: 'public-key' }],
    timeout: 60000,
  })
})

authRouter.post('/api/admin/passkey/admins/:adminId/register/verify', requireAuth, (req, res) => {
  const adminId = Number(req.params.adminId)
  const existing = store.passkeys.get(adminId) ?? []
  const item = {
    id: nextId(existing),
    name: String(req.body?.name || 'Admin Passkey'),
    credential_id: String(req.body?.id || crypto.randomBytes(16).toString('base64url')),
    created_at: new Date().toISOString(),
    last_used_at: null,
  }
  existing.push(item)
  store.passkeys.set(adminId, existing)
  res.json(item)
})

authRouter.delete('/api/admin/passkey/admins/:adminId/:passkeyId', requireAuth, (req, res) => {
  const adminId = Number(req.params.adminId)
  const passkeyId = Number(req.params.passkeyId)
  const list = store.passkeys.get(adminId) ?? []
  store.passkeys.set(adminId, list.filter(p => p.id !== passkeyId))
  res.json({ detail: 'Passkey removed' })
})

authRouter.delete('/api/admin/passkey/:passkeyId', requireAuth, (req: AuthedRequest, res) => {
  const passkeyId = Number(req.params.passkeyId)
  const list = store.passkeys.get(req.admin!.id) ?? []
  store.passkeys.set(req.admin!.id, list.filter(p => p.id !== passkeyId))
  res.json({ detail: 'Passkey removed' })
})

// Single Admin routes (by-id, by-username, and legacy :username)
for (const prefix of ['/api/admin/by-id/:ident', '/api/admin/by-username/:ident', '/api/admin/:ident']) {
  authRouter.put(prefix, requireAuth, (req, res) => {
    const admin = findAdminByParam(req.params.ident)
    if (!admin) {
      res.status(404).json({ detail: 'Admin not found' })
      return
    }
    res.json(updateAdminRecord(admin, req.body || {}))
  })

  authRouter.delete(prefix, requireAuth, (req, res) => {
    const admin = findAdminByParam(req.params.ident)
    if (!admin) {
      res.status(404).json({ detail: 'Admin not found' })
      return
    }
    if (admin.id === 1) {
      res.status(400).json({ detail: 'Cannot delete primary owner' })
      return
    }
    store.admins = store.admins.filter(a => a.id !== admin.id)
    res.json({ detail: 'Admin deleted' })
  })

  authRouter.get(`${prefix}/usage`, requireAuth, (req, res) => {
    const admin = findAdminByParam(req.params.ident)
    if (!admin) {
      res.status(404).json({ detail: 'Admin not found' })
      return
    }
    const end = new Date()
    const start = new Date(end.getTime() - 7 * 86400_000)
    res.json({
      period: String(req.query.period || 'day'),
      start: start.toISOString(),
      end: end.toISOString(),
      stats: {
        [admin.username]: Array.from({ length: 7 }, (_, i) => ({
          period_start: new Date(start.getTime() + i * 86400_000).toISOString(),
          total_traffic: Math.round((admin.used_traffic / 7) * (0.7 + (i % 3) * 0.25)),
        })),
      },
    })
  })

  authRouter.post(`${prefix}/users/disable`, requireAuth, (req, res) => {
    const admin = findAdminByParam(req.params.ident)
    if (!admin) {
      res.status(404).json({ detail: 'Admin not found' })
      return
    }
    for (const u of store.users) {
      if (u.admin_id === admin.id && u.status === 'active') u.status = 'disabled'
    }
    res.json({ detail: 'Users disabled' })
  })

  authRouter.post(`${prefix}/users/activate`, requireAuth, (req, res) => {
    const admin = findAdminByParam(req.params.ident)
    if (!admin) {
      res.status(404).json({ detail: 'Admin not found' })
      return
    }
    for (const u of store.users) {
      if (u.admin_id === admin.id && u.status === 'disabled') u.status = 'active'
    }
    res.json({ detail: 'Users activated' })
  })

  authRouter.delete(`${prefix}/users`, requireAuth, (req, res) => {
    const admin = findAdminByParam(req.params.ident)
    if (!admin) {
      res.status(404).json({ detail: 'Admin not found' })
      return
    }
    store.users = store.users.filter(u => u.admin_id !== admin.id)
    res.json({ detail: 'Users deleted' })
  })

  authRouter.post(`${prefix}/reset`, requireAuth, (req, res) => {
    const admin = findAdminByParam(req.params.ident)
    if (!admin) {
      res.status(404).json({ detail: 'Admin not found' })
      return
    }
    admin.used_traffic = 0
    admin.is_limited = false
    admin.status = admin.is_disabled ? 'disabled' : 'active'
    res.json(buildAdminDetails(admin))
  })
}

// Roles
authRouter.get('/api/admin-roles', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 50)
  const name = String(req.query.name || '').toLowerCase()
  let roles = store.roles
  if (name) roles = roles.filter(r => r.name.toLowerCase().includes(name))
  res.json({ roles: roles.slice(offset, offset + limit).map(formatRole), total: roles.length })
})

authRouter.get('/api/admin-roles/simple', requireAuth, (_req, res) => {
  res.json({
    roles: store.roles.map(r => ({ id: r.id, name: r.name, is_owner: r.is_owner })),
    total: store.roles.length,
  })
})

authRouter.get('/api/admin-role/:roleId', requireAuth, (req, res) => {
  const role = store.roles.find(r => r.id === Number(req.params.roleId))
  if (!role) {
    res.status(404).json({ detail: 'Role not found' })
    return
  }
  res.json(formatRole(role))
})

authRouter.post('/api/admin-role', requireAuth, (req, res) => {
  const b = req.body || {}
  const role: StoredRole = {
    id: nextId(store.roles),
    name: String(b.name || 'Custom Role'),
    is_owner: Boolean(b.is_owner),
    permissions: b.permissions || {},
    limits: b.limits || {},
    features: b.features || { can_use_reset_strategy: true, can_use_next_plan: true },
    access: b.access || { require_template: false, allowed_template_ids: null, allowed_group_ids: null },
    hwid: b.hwid || { enabled: false, forced: false, require_hwid_for_manual_sub: false, fallback_limit: null, min_limit: null, max_limit: null, mode: 'use_global' },
    disabled_when_limited: Boolean(b.disabled_when_limited),
    disconnect_users_when_limited: Boolean(b.disconnect_users_when_limited),
    disconnect_users_when_disabled: Boolean(b.disconnect_users_when_disabled),
    created_at: new Date().toISOString(),
  }
  store.roles.push(role)
  res.status(201).json(formatRole(role))
})

authRouter.put('/api/admin-role/:roleId', requireAuth, (req, res) => {
  const role = store.roles.find(r => r.id === Number(req.params.roleId))
  if (!role) {
    res.status(404).json({ detail: 'Role not found' })
    return
  }
  const b = req.body || {}
  if (b.name !== undefined) role.name = String(b.name)
  if (b.permissions !== undefined) role.permissions = b.permissions
  if (b.limits !== undefined) role.limits = b.limits
  if (b.features !== undefined) role.features = b.features
  if (b.access !== undefined) role.access = b.access
  if (b.hwid !== undefined) role.hwid = b.hwid
  if (b.disabled_when_limited !== undefined) role.disabled_when_limited = Boolean(b.disabled_when_limited)
  if (b.disconnect_users_when_limited !== undefined) role.disconnect_users_when_limited = Boolean(b.disconnect_users_when_limited)
  if (b.disconnect_users_when_disabled !== undefined) role.disconnect_users_when_disabled = Boolean(b.disconnect_users_when_disabled)
  res.json(formatRole(role))
})

authRouter.delete('/api/admin-role/:roleId', requireAuth, (req, res) => {
  const id = Number(req.params.roleId)
  if (id === 1) {
    res.status(400).json({ detail: 'Cannot delete Owner role' })
    return
  }
  store.roles = store.roles.filter(r => r.id !== id)
  res.json({ detail: 'Role deleted' })
})

// API Keys
authRouter.get('/api/api_keys', requireAuth, (req, res) => {
  const offset = Number(req.query.offset || 0)
  const limit = Number(req.query.limit || 50)
  res.json({
    api_keys: store.apiKeys.slice(offset, offset + limit).map(formatApiKey),
    total: store.apiKeys.length,
  })
})

authRouter.post('/api/api_key', requireAuth, (req: AuthedRequest, res) => {
  const b = req.body || {}
  const raw = `pg_key_${crypto.randomBytes(16).toString('hex')}`
  const item: StoredApiKey = {
    id: nextId(store.apiKeys),
    name: String(b.name || 'API Key'),
    note: b.note ?? null,
    prefix: raw.slice(0, 12),
    raw_key: raw,
    permissions: b.permissions || {},
    inherit_permissions: Boolean(b.inherit_permissions),
    expire_date: b.expire_date ?? null,
    max_requests: b.max_requests != null ? Number(b.max_requests) : null,
    requests_used: 0,
    delete_on_limit: Boolean(b.delete_on_limit),
    admin_id: req.admin!.id,
    is_expired: false,
    is_usable: true,
    created_at: new Date().toISOString(),
    last_used_at: null,
  }
  store.apiKeys.push(item)
  res.status(201).json({ ...formatApiKey(item), key: raw })
})

authRouter.post('/api/api_keys/bulk/delete', requireAuth, (req, res) => {
  const ids = new Set<number>((req.body?.ids || []).map(Number))
  store.apiKeys = store.apiKeys.filter(k => !ids.has(k.id))
  res.json({ detail: 'Deleted' })
})

authRouter.get('/api/api_key/:keyId', requireAuth, (req, res) => {
  const item = store.apiKeys.find(k => k.id === Number(req.params.keyId))
  if (!item) {
    res.status(404).json({ detail: 'API key not found' })
    return
  }
  res.json(formatApiKey(item))
})

authRouter.patch('/api/api_key/:keyId', requireAuth, (req, res) => {
  const item = store.apiKeys.find(k => k.id === Number(req.params.keyId))
  if (!item) {
    res.status(404).json({ detail: 'API key not found' })
    return
  }
  const b = req.body || {}
  if (b.name !== undefined) item.name = String(b.name)
  if (b.note !== undefined) item.note = b.note
  if (b.permissions !== undefined) item.permissions = b.permissions
  if (b.inherit_permissions !== undefined) item.inherit_permissions = Boolean(b.inherit_permissions)
  if (b.expire_date !== undefined) item.expire_date = b.expire_date
  if (b.max_requests !== undefined) item.max_requests = b.max_requests != null ? Number(b.max_requests) : null
  if (b.delete_on_limit !== undefined) item.delete_on_limit = Boolean(b.delete_on_limit)
  res.json(formatApiKey(item))
})

authRouter.delete('/api/api_key/:keyId', requireAuth, (req, res) => {
  store.apiKeys = store.apiKeys.filter(k => k.id !== Number(req.params.keyId))
  res.json({ detail: 'API key deleted' })
})

authRouter.post('/api/api_key/:keyId/revoke', requireAuth, (req, res) => {
  const item = store.apiKeys.find(k => k.id === Number(req.params.keyId))
  if (!item) {
    res.status(404).json({ detail: 'API key not found' })
    return
  }
  item.is_usable = false
  res.json(formatApiKey(item))
})
