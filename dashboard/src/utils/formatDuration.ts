import type { TFunction } from 'i18next'

export function formatDuration(seconds: number | null | undefined, t?: TFunction): string {
  const total = Math.max(0, Math.floor(Number(seconds ?? 0)))
  if (!Number.isFinite(total)) return '0s'

  const days = Math.floor(total / 86400)
  const hours = Math.floor((total % 86400) / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60

  const unitLabel = (key: string, count: number, fallback: string) => {
    if (!t) return fallback
    return t(`time.${count === 1 ? key : `${key}s`}`, { defaultValue: fallback })
  }

  const parts: string[] = []
  if (days > 0) parts.push(`${days}${t ? ` ${unitLabel('day', days, 'd')}` : 'd'}`)
  if (hours > 0) parts.push(`${hours}${t ? ` ${unitLabel('hour', hours, 'h')}` : 'h'}`)
  if (minutes > 0 && parts.length < 2) parts.push(`${minutes}${t ? ` ${unitLabel('minute', minutes, 'm')}` : 'm'}`)
  if (parts.length === 0) parts.push(`${secs}${t ? ` ${unitLabel('second', secs, 's')}` : 's'}`)

  return parts.join(' ')
}
