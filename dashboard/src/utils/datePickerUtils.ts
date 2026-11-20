import { format as formatGregorian } from 'date-fns'
import { format as formatJalali } from 'date-fns-jalali'
import { parseDateInput } from './dateTimeParsing'

export function isPersianLocaleLanguage(lang?: string | null): boolean {
  if (!lang) return false
  return lang.toLowerCase().startsWith('fa')
}

export function formatDateByLocale(
  date: Date | number | string | null | undefined,
  isPersian: boolean,
  showTime = false,
): string {
  if (!date) return ''
  const d = date instanceof Date ? date : parseDateInput(date).toDate()
  if (Number.isNaN(d.getTime())) return ''
  const pattern = showTime ? 'yyyy/MM/dd HH:mm' : 'yyyy/MM/dd'
  try {
    return isPersian ? formatJalali(d, pattern) : formatGregorian(d, pattern)
  } catch {
    return formatGregorian(d, pattern)
  }
}

export function formatDateShort(date: Date | undefined, isPersian: boolean): string {
  if (!date || Number.isNaN(date.getTime())) return ''
  try {
    return isPersian ? formatJalali(date, 'MM/dd') : formatGregorian(date, 'MMM d')
  } catch {
    return formatGregorian(date, 'MMM d')
  }
}

export function isDateDisabled(date: Date, minDate?: Date, maxDate?: Date): boolean {
  const targetDay = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  if (minDate) {
    const minDay = new Date(minDate.getFullYear(), minDate.getMonth(), minDate.getDate()).getTime()
    if (targetDay < minDay) return true
  }
  if (maxDate) {
    const maxDay = new Date(maxDate.getFullYear(), maxDate.getMonth(), maxDate.getDate()).getTime()
    if (targetDay > maxDay) return true
  }
  return false
}

export function serializeDatePickerValue(
  date: Date | undefined,
  options?: { useUtcTimestamp?: boolean },
): string {
  if (!date || Number.isNaN(date.getTime())) return ''
  if (options?.useUtcTimestamp) {
    return date.toISOString()
  }
  return date.toISOString()
}

export function toDatePickerDisplayDate(value: string | number | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') return null
  const parsed = parseDateInput(value)
  if (!parsed.isValid()) return null
  return parsed.toDate()
}

export function normalizeDatePickerValueForSubmit(
  value: string | number | Date | null | undefined,
): string | number | null | undefined {
  if (value === null || value === undefined || value === '') return undefined
  const parsed = parseDateInput(value)
  if (!parsed.isValid()) return undefined
  return parsed.toISOString()
}
