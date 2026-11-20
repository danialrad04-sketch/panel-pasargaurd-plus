import { getAuthToken } from './authStorage'

export type SubscriptionContentFormat =
  | 'links'
  | 'v2ray'
  | 'singbox'
  | 'clash'
  | 'clash-meta'
  | 'wireguard'
  | 'outline'
  | 'raw'
  | string

function getWindowOrigin(): string {
  if (typeof window !== 'undefined' && window.location?.origin) {
    return window.location.origin
  }
  return 'http://localhost:3000'
}

export function resolveSubscriptionPanelBaseUrl(override?: string | null): string {
  const configured =
    override?.trim() ||
    (typeof import.meta !== 'undefined' && typeof import.meta.env?.VITE_BASE_API === 'string'
      ? import.meta.env.VITE_BASE_API.trim()
      : '')

  if (configured && configured !== '/' && /^https?:\/\//i.test(configured)) {
    return configured.replace(/\/+$/, '')
  }
  return getWindowOrigin()
}

export function resolveSubscriptionPublicUrl(subscriptionUrl?: string | null): string {
  const raw = (subscriptionUrl || '').trim()
  if (!raw) return ''
  if (/^https?:\/\//i.test(raw)) return raw
  const base = resolveSubscriptionPanelBaseUrl()
  return `${base}${raw.startsWith('/') ? '' : '/'}${raw}`
}

export function resolveSubscriptionQrUrl(subscriptionUrl?: string | null): string {
  return resolveSubscriptionPublicUrl(subscriptionUrl)
}

export function buildSubscriptionFormatUrl(
  subscriptionUrl: string | null | undefined,
  format: SubscriptionContentFormat,
): string {
  const base = resolveSubscriptionPublicUrl(subscriptionUrl)
  if (!base) return ''
  if (!format || format === 'raw' || format === 'links') {
    return format === 'links' ? `${base.replace(/\/+$/, '')}/links` : base
  }
  return `${base.replace(/\/+$/, '')}/${encodeURIComponent(format)}`
}

export async function fetchUserSubscriptionContent(
  userId: number,
  format: SubscriptionContentFormat = 'links',
  timeoutMs = 15000,
): Promise<string> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const token = getAuthToken()
    const base = resolveSubscriptionPanelBaseUrl()
    const res = await fetch(`${base}/api/user/${userId}/subscription?format=${encodeURIComponent(format)}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      signal: controller.signal,
    })
    if (!res.ok) {
      throw new Error(`Failed to fetch subscription content (${res.status})`)
    }
    return await res.text()
  } finally {
    clearTimeout(timer)
  }
}

export async function fetchSubscriptionBlobFromUrl(url: string): Promise<Blob> {
  const resolved = resolveSubscriptionPublicUrl(url)
  const token = getAuthToken()
  const res = await fetch(resolved, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  })
  if (!res.ok) {
    throw new Error(`Failed to download subscription (${res.status})`)
  }
  return await res.blob()
}

export function extractNameFromConfigUrl(configUrl: string): string {
  const trimmed = (configUrl || '').trim()
  if (!trimmed) return ''
  const hashIdx = trimmed.indexOf('#')
  if (hashIdx !== -1) {
    const fragment = trimmed.slice(hashIdx + 1).trim()
    if (fragment) {
      try {
        return decodeURIComponent(fragment)
      } catch {
        return fragment
      }
    }
  }
  if (trimmed.startsWith('vmess://')) {
    try {
      const jsonStr = atob(trimmed.slice('vmess://'.length))
      const parsed = JSON.parse(jsonStr) as { ps?: string }
      if (parsed?.ps) return String(parsed.ps)
    } catch {
      // ignore invalid base64
    }
  }
  return trimmed.split('://')[0]?.toUpperCase() || ''
}

export function extractAddressFromConfigUrl(configUrl: string): string {
  const trimmed = (configUrl || '').trim()
  if (!trimmed) return ''
  if (trimmed.startsWith('vmess://')) {
    try {
      const parsed = JSON.parse(atob(trimmed.slice('vmess://'.length))) as { add?: string; port?: number | string }
      if (parsed?.add) return parsed.port ? `${parsed.add}:${parsed.port}` : String(parsed.add)
    } catch {
      // ignore
    }
  }
  if (trimmed.startsWith('[Interface]')) {
    const endpointMatch = trimmed.match(/^Endpoint\s*=\s*(.+)$/im)
    return endpointMatch ? endpointMatch[1].trim() : ''
  }
  try {
    const atIdx = trimmed.indexOf('@')
    if (atIdx !== -1) {
      const afterAt = trimmed.slice(atIdx + 1)
      return afterAt.split(/[/?#]/)[0] || ''
    }
    const url = new URL(trimmed)
    return url.host
  } catch {
    return ''
  }
}

export function prepareSubscriptionContentForCopy(raw: string): { content: string } {
  return { content: (raw || '').trim() }
}

export function encodeSubscriptionContentToBase64(content: string): string {
  try {
    const bytes = new TextEncoder().encode(content)
    let binary = ''
    for (let i = 0; i < bytes.length; i++) {
      binary += String.fromCharCode(bytes[i])
    }
    return btoa(binary)
  } catch {
    return btoa(content)
  }
}

export interface WireGuardDownloadPayload {
  content: string
  fileName: string
  mimeType: string
}

export function getWireGuardDownloadPayload(config: string): WireGuardDownloadPayload | null {
  const trimmed = (config || '').trim()
  if (!trimmed) return null
  const isWgIni = trimmed.includes('[Interface]') && trimmed.includes('[Peer]')
  const isWgUri = trimmed.toLowerCase().startsWith('wireguard://') || trimmed.toLowerCase().startsWith('wg://')
  if (!isWgIni && !isWgUri) return null

  let iniContent = trimmed
  let fileName = 'wireguard.conf'
  if (isWgUri) {
    try {
      const url = new URL(trimmed)
      const priv = decodeURIComponent(url.username || '')
      const pub = url.searchParams.get('publickey') || ''
      const addr = url.searchParams.get('address') || '10.0.0.2/32'
      const dns = url.searchParams.get('dns') || '1.1.1.1'
      const mtu = url.searchParams.get('mtu') || '1280'
      const label = url.hash ? decodeURIComponent(url.hash.slice(1)).replace(/[^a-zA-Z0-9_-]/g, '_') : 'wireguard'
      if (label) fileName = `${label}.conf`
      iniContent = [
        '[Interface]',
        `PrivateKey = ${priv}`,
        `Address = ${addr}`,
        `DNS = ${dns}`,
        `MTU = ${mtu}`,
        '',
        '[Peer]',
        `PublicKey = ${pub}`,
        `AllowedIPs = 0.0.0.0/0, ::/0`,
        `Endpoint = ${url.host}`,
      ].join('\n')
    } catch {
      iniContent = trimmed
    }
  }

  return {
    content: iniContent,
    fileName,
    mimeType: 'text/plain;charset=utf-8',
  }
}

export function downloadTextFile(content: string, fileName: string, mimeType = 'text/plain;charset=utf-8'): void {
  if (typeof document === 'undefined') return
  const blob = new Blob([content], { type: mimeType })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
