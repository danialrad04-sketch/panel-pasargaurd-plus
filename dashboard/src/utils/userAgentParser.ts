export type ClientIconType =
  | 'ios'
  | 'android'
  | 'windows'
  | 'macos'
  | 'linux'
  | 'browser'
  | 'app'
  | 'unknown'

export interface ParsedUserAgent {
  name: string
  version?: string
  os?: string
  iconType: ClientIconType
  isKnownClient: boolean
  raw: string
}

const KNOWN_CLIENTS: Array<{ match: RegExp; name: string; iconType?: ClientIconType }> = [
  { match: /v2rayng/i, name: 'v2rayNG', iconType: 'android' },
  { match: /v2rayn/i, name: 'v2rayN', iconType: 'windows' },
  { match: /streisand/i, name: 'Streisand', iconType: 'ios' },
  { match: /shadowrocket/i, name: 'Shadowrocket', iconType: 'ios' },
  { match: /nekobox|nekoray/i, name: 'NekoBox', iconType: 'android' },
  { match: /hiddify/i, name: 'Hiddify', iconType: 'app' },
  { match: /happ/i, name: 'Happ', iconType: 'ios' },
  { match: /flclash/i, name: 'FlClash', iconType: 'app' },
  { match: /clash[-\s]?meta|mihomo/i, name: 'Mihomo', iconType: 'app' },
  { match: /clash/i, name: 'Clash', iconType: 'app' },
  { match: /sing-box|sfa|sfi|sfm/i, name: 'sing-box', iconType: 'app' },
  { match: /karing/i, name: 'Karing', iconType: 'ios' },
  { match: /v2box/i, name: 'V2Box', iconType: 'ios' },
  { match: /foxray/i, name: 'FoXray', iconType: 'ios' },
  { match: /loon/i, name: 'Loon', iconType: 'ios' },
  { match: /surge/i, name: 'Surge', iconType: 'ios' },
  { match: /quantumult/i, name: 'Quantumult X', iconType: 'ios' },
  { match: /curl/i, name: 'curl', iconType: 'linux' },
  { match: /mozilla/i, name: 'Browser', iconType: 'browser' },
]

export function parseUserAgent(userAgent?: string | null): ParsedUserAgent {
  const raw = (userAgent || '').trim()
  if (!raw) {
    return { name: 'Unknown Client', iconType: 'unknown', isKnownClient: false, raw: '' }
  }

  let detectedOs: string | undefined
  let osIcon: ClientIconType = 'unknown'

  if (/iphone|ipad|ios|darwin/i.test(raw)) {
    detectedOs = 'iOS'
    osIcon = 'ios'
  } else if (/android/i.test(raw)) {
    detectedOs = 'Android'
    osIcon = 'android'
  } else if (/windows/i.test(raw)) {
    detectedOs = 'Windows'
    osIcon = 'windows'
  } else if (/mac os x|macintosh|macos/i.test(raw)) {
    detectedOs = 'macOS'
    osIcon = 'macos'
  } else if (/linux/i.test(raw)) {
    detectedOs = 'Linux'
    osIcon = 'linux'
  }

  const versionMatch = raw.match(/\/([0-9]+(?:\.[0-9]+)*)/)
  const version = versionMatch?.[1]

  for (const client of KNOWN_CLIENTS) {
    if (client.match.test(raw)) {
      return {
        name: client.name,
        version,
        os: detectedOs,
        iconType: osIcon !== 'unknown' ? osIcon : client.iconType ?? 'app',
        isKnownClient: true,
        raw,
      }
    }
  }

  const firstToken = raw.split(/[\s/]+/)[0]
  return {
    name: firstToken || 'Unknown Client',
    version,
    os: detectedOs,
    iconType: osIcon !== 'unknown' ? osIcon : 'app',
    isKnownClient: false,
    raw,
  }
}

export function formatClientInfo(info: ParsedUserAgent): string {
  const parts = [info.name]
  if (info.version) parts.push(`v${info.version}`)
  if (info.os) parts.push(`(${info.os})`)
  return parts.join(' ')
}
