import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

dayjs.extend(utc)
dayjs.extend(timezone)

export function parseDateInput(input: string | number | Date | null | undefined): dayjs.Dayjs {
  if (input == null || input === '') return dayjs(NaN)
  if (input instanceof Date) return dayjs(input)
  if (typeof input === 'number') {
    if (!Number.isFinite(input)) return dayjs(NaN)
    return input > 1e12 ? dayjs(input) : dayjs.unix(input)
  }
  const trimmed = String(input).trim()
  if (!trimmed) return dayjs(NaN)
  if (/^\d+(\.\d+)?$/.test(trimmed)) {
    const num = Number(trimmed)
    return num > 1e12 ? dayjs(num) : dayjs.unix(num)
  }
  return dayjs(trimmed)
}

export function formatOffsetDateTime(date: Date | number | string): string {
  const d = parseDateInput(date)
  if (!d.isValid()) return new Date().toISOString()
  return d.format('YYYY-MM-DDTHH:mm:ssZ')
}
