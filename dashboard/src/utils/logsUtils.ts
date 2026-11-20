export type LogLevel = 'info' | 'warning' | 'debug' | 'error'

export interface LogLine {
  id: string
  timestamp: Date | null
  rawTimestamp: string | null
  type: LogLevel
  message: string
}

export function appendTrim<T>(prev: T[], batch: T[], maxItems: number): T[] {
  if (batch.length === 0) return prev
  const combined = [...prev, ...batch]
  if (combined.length <= maxItems) return combined
  return combined.slice(combined.length - maxItems)
}

function detectLogLevel(line: string): LogLevel {
  const lower = line.toLowerCase()
  if (/\b(error|err|fatal|panic|crit|critical|exception)\b|\[error\]/i.test(lower)) return 'error'
  if (/\b(warn|warning)\b|\[warn(ing)?\]/i.test(lower)) return 'warning'
  if (/\b(debug|trace)\b|\[debug\]/i.test(lower)) return 'debug'
  return 'info'
}

export function parseLogs(rawData: string): LogLine[] {
  if (!rawData || typeof rawData !== 'string') return []
  const lines = rawData.split(/\r?\n/).filter(l => l.trim().length > 0)
  return lines.map((line, index) => {
    // Try extracting leading ISO / datetime timestamp
    const isoMatch = line.match(
      /^(\d{4}[-/]\d{2}[-/]\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)\s+(.*)$/,
    )
    let timestamp: Date | null = null
    let rawTimestamp: string | null = null
    let message = line

    if (isoMatch) {
      rawTimestamp = isoMatch[1]
      const parsed = new Date(isoMatch[1])
      if (!Number.isNaN(parsed.getTime())) {
        timestamp = parsed
      }
      message = isoMatch[2]
    } else {
      const timeMatch = line.match(/^(\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+(.*)$/)
      if (timeMatch) {
        rawTimestamp = timeMatch[1]
        message = timeMatch[2]
      }
    }

    return {
      id: `${Date.now()}-${index}-${Math.random().toString(36).slice(2, 7)}`,
      timestamp,
      rawTimestamp,
      type: detectLogLevel(message),
      message,
    }
  })
}

export function getLogStyle(level: LogLevel): {
  type: LogLevel
  variant: 'default' | 'secondary' | 'destructive' | 'outline'
  color: string
} {
  switch (level) {
    case 'error':
      return { type: 'error', variant: 'destructive', color: 'bg-red-500' }
    case 'warning':
      return { type: 'warning', variant: 'outline', color: 'bg-amber-500' }
    case 'debug':
      return { type: 'debug', variant: 'secondary', color: 'bg-orange-400' }
    case 'info':
    default:
      return { type: 'info', variant: 'default', color: 'bg-emerald-500' }
  }
}
