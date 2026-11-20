import type { TFunction } from 'i18next'
import type { DateRange } from 'react-day-picker'
import { format as formatGregorian } from 'date-fns'
import { format as formatJalali } from 'date-fns-jalali'
import { Period } from '@/service/api'

export const CHART_PERIOD_OVERRIDE_AUTO = 'auto' as const
export const CHART_PERIOD_VALUES = [Period.minute, Period.hour, Period.day, Period.month] as const
export const USAGE_RECORD_BUCKET_MINUTES = 10

export type ChartPeriodOverride = typeof CHART_PERIOD_OVERRIDE_AUTO | Period
export type TrafficShortcutKey =
  | '1h'
  | '2h'
  | '4h'
  | '6h'
  | '12h'
  | '24h'
  | '2d'
  | '3d'
  | '5d'
  | '7d'
  | '1w'
  | '2w'
  | '1m'
  | '30d'
  | '3m'
  | '1y'
  | 'all'
  | string

export interface ChartPeriodLimitOptions {
  recordBucketMinutes?: number
}

export interface ChartRangeSelection {
  shortcut?: TrafficShortcutKey
  customRange?: DateRange
}

export interface PeriodOption {
  label: string
  value: TrafficShortcutKey
  period: Period
  hours?: number
  days?: number
  months?: number
  allTime?: boolean
}

export function resolvePeriodOverride(override: ChartPeriodOverride): Period | undefined {
  if (!override || override === CHART_PERIOD_OVERRIDE_AUTO) return undefined
  return override
}

export function getChartPeriodLabel(period: ChartPeriodOverride, t: TFunction): string {
  if (period === CHART_PERIOD_OVERRIDE_AUTO) {
    return t('statistics.periodAuto', { defaultValue: 'Auto' })
  }
  switch (period) {
    case Period.minute:
      return t('time.minute', { defaultValue: 'Minute' })
    case Period.hour:
      return t('time.hour', { defaultValue: 'Hour' })
    case Period.day:
      return t('time.day', { defaultValue: 'Day' })
    case Period.month:
      return t('time.month', { defaultValue: 'Month' })
    default:
      return String(period)
  }
}

function shortcutToDurationMs(shortcut?: TrafficShortcutKey): number {
  switch (shortcut) {
    case '1h':
      return 3600_000
    case '2h':
      return 2 * 3600_000
    case '4h':
      return 4 * 3600_000
    case '6h':
      return 6 * 3600_000
    case '12h':
      return 12 * 3600_000
    case '24h':
      return 24 * 3600_000
    case '2d':
      return 2 * 86400_000
    case '3d':
      return 3 * 86400_000
    case '5d':
      return 5 * 86400_000
    case '7d':
    case '1w':
      return 7 * 86400_000
    case '2w':
      return 14 * 86400_000
    case '1m':
    case '30d':
      return 30 * 86400_000
    case '3m':
      return 90 * 86400_000
    case '1y':
      return 365 * 86400_000
    case 'all':
      return 180 * 86400_000
    default:
      return 7 * 86400_000
  }
}

export function getAllowedChartPeriods(
  selection: ChartRangeSelection,
  options?: ChartPeriodLimitOptions,
): readonly ChartPeriodOverride[] {
  let durationMs = shortcutToDurationMs(selection.shortcut)
  if (selection.customRange?.from && selection.customRange?.to) {
    durationMs = Math.max(3600_000, selection.customRange.to.getTime() - selection.customRange.from.getTime())
  }
  const bucketMin = options?.recordBucketMinutes ?? 1
  const allowed: ChartPeriodOverride[] = [CHART_PERIOD_OVERRIDE_AUTO]
  if (durationMs <= 12 * 3600_000 * bucketMin) allowed.push(Period.minute)
  if (durationMs <= 31 * 86400_000) allowed.push(Period.hour)
  if (durationMs <= 366 * 86400_000) allowed.push(Period.day)
  allowed.push(Period.month)
  return allowed
}

function defaultPeriodForDuration(durationMs: number, shortcut?: string, minuteForOneHour = true): Period {
  if (minuteForOneHour && (shortcut === '1h' || durationMs <= 2 * 3600_000)) return Period.minute
  if (durationMs <= 4 * 86400_000) return Period.hour
  if (durationMs <= 90 * 86400_000) return Period.day
  return Period.month
}

export interface ChartQueryRange {
  startDate: string
  endDate: string
  period: Period
}

export function getChartQueryRangeFromShortcut(
  shortcut: TrafficShortcutKey,
  now: Date = new Date(),
  options?: { minuteForOneHour?: boolean; periodOverride?: Period },
): ChartQueryRange {
  const durationMs = shortcutToDurationMs(shortcut)
  const end = new Date(now)
  const start =
    shortcut === 'all'
      ? new Date(now.getTime() - 90 * 86400_000)
      : new Date(now.getTime() - durationMs)
  const period =
    options?.periodOverride ??
    defaultPeriodForDuration(durationMs, String(shortcut), options?.minuteForOneHour ?? true)

  return {
    startDate: start.toISOString(),
    endDate: end.toISOString(),
    period,
  }
}

export function getChartQueryRangeFromDateRange(
  customRange: DateRange,
  fallbackShortcut: TrafficShortcutKey = '1w',
  options?: { minuteForOneHour?: boolean; periodOverride?: Period },
): ChartQueryRange {
  if (!customRange?.from) {
    return getChartQueryRangeFromShortcut(fallbackShortcut, new Date(), options)
  }
  const start = new Date(customRange.from)
  start.setHours(0, 0, 0, 0)
  const end = new Date(customRange.to ?? customRange.from)
  end.setHours(23, 59, 59, 999)
  const durationMs = Math.max(3600_000, end.getTime() - start.getTime())
  const period =
    options?.periodOverride ?? defaultPeriodForDuration(durationMs, undefined, options?.minuteForOneHour ?? true)

  return {
    startDate: start.toISOString(),
    endDate: end.toISOString(),
    period,
  }
}

function isPersian(locale?: string): boolean {
  return Boolean(locale && locale.toLowerCase().startsWith('fa'))
}

function safeFormat(date: Date, pattern: string, locale?: string): string {
  try {
    return isPersian(locale) ? formatJalali(date, pattern) : formatGregorian(date, pattern)
  } catch {
    return formatGregorian(date, pattern)
  }
}

export function formatPeriodLabelForPeriod(
  periodStart: string,
  period: Period,
  locale?: string,
  _rangeHint?: {
    shortcut?: TrafficShortcutKey
    customRange?: DateRange
    hours?: number
    days?: number
    months?: number
    allTime?: boolean
  },
): string {
  const d = new Date(periodStart)
  if (Number.isNaN(d.getTime())) return String(periodStart || '')
  switch (period) {
    case Period.minute:
      return safeFormat(d, 'HH:mm', locale)
    case Period.hour:
      return safeFormat(d, 'MM/dd HH:00', locale)
    case Period.day:
      return safeFormat(d, 'MM/dd', locale)
    case Period.month:
      return safeFormat(d, 'yyyy/MM', locale)
    default:
      return safeFormat(d, 'MM/dd HH:mm', locale)
  }
}

export function formatPeriodLabel(
  periodStart: string,
  periodOption: PeriodOption,
  locale?: string,
): string {
  return formatPeriodLabelForPeriod(periodStart, periodOption.period, locale, {
    shortcut: periodOption.value,
  })
}

export function formatTooltipDate(
  periodStart: string,
  period: Period,
  locale?: string,
): string {
  const d = new Date(periodStart)
  if (Number.isNaN(d.getTime())) return String(periodStart || '')
  switch (period) {
    case Period.minute:
    case Period.hour:
      return safeFormat(d, 'yyyy/MM/dd HH:mm', locale)
    case Period.day:
      return safeFormat(d, 'yyyy/MM/dd', locale)
    case Period.month:
      return safeFormat(d, 'yyyy/MM', locale)
    default:
      return safeFormat(d, 'yyyy/MM/dd HH:mm', locale)
  }
}

export function getChartXAxisInterval(params: {
  dataLength: number
  period: Period
  shortcut?: TrafficShortcutKey
  windowWidth?: number
  customRange?: DateRange
  periodOverride?: Period
}): number {
  const { dataLength, windowWidth = 1024 } = params
  if (dataLength <= 1) return 0
  const maxTicks = windowWidth < 640 ? 5 : windowWidth < 1024 ? 8 : 12
  if (dataLength <= maxTicks) return 0
  return Math.max(0, Math.ceil(dataLength / maxTicks) - 1)
}

export function toStatsRecord<T>(stats: unknown): Record<string, T[]> {
  if (!stats) return {}
  if (Array.isArray(stats)) {
    return { '-1': stats as T[] }
  }
  if (typeof stats === 'object') {
    return stats as Record<string, T[]>
  }
  return {}
}

export function pickStatsArray<T>(stats: unknown, preferredKeys: string[] = ['-1']): T[] {
  if (!stats) return []
  if (Array.isArray(stats)) return stats as T[]
  if (typeof stats === 'object') {
    const rec = stats as Record<string, T[]>
    for (const key of preferredKeys) {
      if (Array.isArray(rec[key])) return rec[key]
    }
    const firstKey = Object.keys(rec)[0]
    if (firstKey && Array.isArray(rec[firstKey])) return rec[firstKey]
  }
  return []
}

export function buildPeriodOptions(t: TFunction): PeriodOption[] {
  return [
    {
      label: `1 ${t('time.hour', { defaultValue: 'hour' })}`,
      value: '1h',
      period: Period.minute,
      hours: 1,
    },
    {
      label: `6 ${t('time.hours', { defaultValue: 'hours' })}`,
      value: '6h',
      period: Period.hour,
      hours: 6,
    },
    {
      label: `24 ${t('time.hours', { defaultValue: 'hours' })}`,
      value: '24h',
      period: Period.hour,
      hours: 24,
    },
    {
      label: `3 ${t('time.days', { defaultValue: 'days' })}`,
      value: '3d',
      period: Period.hour,
      days: 3,
    },
    {
      label: `7 ${t('time.days', { defaultValue: 'days' })}`,
      value: '1w',
      period: Period.day,
      days: 7,
    },
    {
      label: `1 ${t('time.month', { defaultValue: 'month' })}`,
      value: '1m',
      period: Period.day,
      months: 1,
    },
  ]
}

export function getDefaultPeriodOption(options: PeriodOption[]): PeriodOption {
  return options.find(opt => opt.value === '24h') ?? options[0]
}

export function getDateRangeForPeriodOption(option: PeriodOption): { startDate: string; endDate: string } {
  const now = new Date()
  let startMs = now.getTime() - 24 * 3600_000
  if (option.hours) startMs = now.getTime() - option.hours * 3600_000
  else if (option.days) startMs = now.getTime() - option.days * 86400_000
  else if (option.months) startMs = now.getTime() - option.months * 30 * 86400_000
  else if (option.allTime) startMs = now.getTime() - 90 * 86400_000

  return {
    startDate: new Date(startMs).toISOString(),
    endDate: now.toISOString(),
  }
}

export function toChartQueryEndDate(endDate: string): string {
  return endDate
}
