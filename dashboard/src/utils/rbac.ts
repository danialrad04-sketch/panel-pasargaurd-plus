import type { AdminDetails } from '@/service/api'

type AdminLike = Pick<AdminDetails, 'role'> | null | undefined

export function isOwner(admin: AdminLike): boolean {
  return Boolean(admin?.role?.is_owner)
}

export function roleLabel(admin: AdminLike): string {
  if (!admin?.role) return 'Admin'
  if (admin.role.name && admin.role.name.trim()) return admin.role.name
  return admin.role.is_owner ? 'Owner' : 'Admin'
}

function getActionValue(admin: AdminLike, resource: string, action: string): unknown {
  if (!admin?.role) return undefined
  if (admin.role.is_owner) return true
  const permissions = admin.role.permissions as Record<string, Record<string, unknown> | null | undefined> | null | undefined
  const resourcePerms = permissions?.[resource]
  if (!resourcePerms || typeof resourcePerms !== 'object') return undefined
  return resourcePerms[action]
}

export function hasPermission(admin: AdminLike, resource: string, action: string): boolean {
  if (!admin?.role) return false
  if (admin.role.is_owner) return true
  const value = getActionValue(admin, resource, action)
  if (value === true) return true
  if (value && typeof value === 'object' && 'scope' in value) {
    const scope = Number((value as { scope?: unknown }).scope)
    return scope === 1 || scope === 2
  }
  return false
}

export function hasScopeAll(admin: AdminLike, resource: string, action: string): boolean {
  if (!admin?.role) return false
  if (admin.role.is_owner) return true
  const value = getActionValue(admin, resource, action)
  if (value === true) return true
  if (value && typeof value === 'object' && 'scope' in value) {
    return Number((value as { scope?: unknown }).scope) === 2
  }
  return false
}

export function canReadResourcePage(admin: AdminLike, resource: string): boolean {
  return hasPermission(admin, resource, 'read')
}

export function canAccessRoute(admin: AdminLike, pathname: string): boolean {
  if (!admin) return false
  if (isOwner(admin)) return true

  const path = pathname.replace(/\/+$/, '') || '/'

  if (path === '/') return hasPermission(admin, 'system', 'read')
  if (path === '/users') return hasPermission(admin, 'users', 'read')
  if (path === '/statistics') return hasPermission(admin, 'nodes', 'stats')
  if (path === '/tunnels' || path === '/smartdns') return isOwner(admin)
  if (path === '/dns') return hasPermission(admin, 'dns', 'read')
  if (path === '/hosts') return canReadResourcePage(admin, 'hosts')
  if (path === '/groups') return canReadResourcePage(admin, 'groups')
  if (path === '/admins') return canReadResourcePage(admin, 'admins')
  if (path === '/admin-roles') return isOwner(admin)
  if (path === '/api-keys') return canReadResourcePage(admin, 'api_keys')

  if (path === '/nodes') return canReadResourcePage(admin, 'nodes')
  if (path.startsWith('/nodes/cores') || path === '/nodes/wireguard') return canReadResourcePage(admin, 'cores')
  if (path === '/nodes/logs') return hasPermission(admin, 'nodes', 'logs')

  if (path === '/templates') return canReadResourcePage(admin, 'templates') || canReadResourcePage(admin, 'client_templates')
  if (path === '/templates/user') return canReadResourcePage(admin, 'templates')
  if (path === '/templates/client') return canReadResourcePage(admin, 'client_templates')

  if (path === '/bulk' || path === '/bulk/create') return hasPermission(admin, 'users', 'create') && canReadResourcePage(admin, 'templates')
  if (path.startsWith('/bulk/')) return hasScopeAll(admin, 'users', 'update')

  if (path === '/settings' || path === '/settings/theme' || path === '/theme') return true
  if (path === '/settings/general') return hasPermission(admin, 'settings', 'read_general') && hasPermission(admin, 'settings', 'update')
  if (path.startsWith('/settings/')) return hasPermission(admin, 'settings', 'read') && hasPermission(admin, 'settings', 'update')

  return true
}

const ORDERED_FALLBACK_ROUTES = [
  '/',
  '/users',
  '/statistics',
  '/hosts',
  '/groups',
  '/nodes',
  '/nodes/cores',
  '/templates/user',
  '/templates/client',
  '/admins',
  '/api-keys',
  '/settings/general',
  '/settings/theme',
]

export function firstAllowedRoute(admin: AdminLike): string {
  for (const route of ORDERED_FALLBACK_ROUTES) {
    if (canAccessRoute(admin, route)) return route
  }
  return '/settings/theme'
}
