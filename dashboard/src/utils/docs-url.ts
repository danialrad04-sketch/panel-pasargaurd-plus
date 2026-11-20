import { DOCUMENTATION } from '@/constants/Project'

const DOCS_PATH_MAP: Record<string, string> = {
  '/users': `${DOCUMENTATION}/docs/users`,
  '/nodes': `${DOCUMENTATION}/docs/nodes`,
  '/nodes/cores': `${DOCUMENTATION}/docs/cores`,
  '/hosts': `${DOCUMENTATION}/docs/hosts`,
  '/groups': `${DOCUMENTATION}/docs/groups`,
  '/templates': `${DOCUMENTATION}/docs/templates`,
  '/templates/user': `${DOCUMENTATION}/docs/templates`,
  '/templates/client': `${DOCUMENTATION}/docs/templates`,
  '/bulk': `${DOCUMENTATION}/docs/bulk`,
  '/settings': `${DOCUMENTATION}/docs/settings`,
  '/admins': `${DOCUMENTATION}/docs/admins`,
  '/tunnels': `${DOCUMENTATION}/docs/tunnels`,
  '/smartdns': `${DOCUMENTATION}/docs/smartdns`,
  '/dns': `${DOCUMENTATION}/docs/dns`,
}

export function getDocsUrl(pathname?: string): string {
  if (!pathname) return DOCUMENTATION
  const normalized = pathname.replace(/\/+$/, '') || '/'
  if (DOCS_PATH_MAP[normalized]) return DOCS_PATH_MAP[normalized]
  const matchedPrefix = Object.keys(DOCS_PATH_MAP).find(prefix => normalized.startsWith(`${prefix}/`))
  return matchedPrefix ? DOCS_PATH_MAP[matchedPrefix] : DOCUMENTATION
}
