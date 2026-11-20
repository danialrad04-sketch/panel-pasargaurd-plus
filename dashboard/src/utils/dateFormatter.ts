import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { UserStatus } from '@/service/api'
import { parseDateInput } from './dateTimeParsing'

dayjs.extend(relativeTime)
dayjs.extend(utc)
dayjs.extend(timezone)

export const dateUtils = {
  getSystemTimeZone(): string {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
    } catch {
      return 'UTC'
    }
  },

  toDayjs(input: string | number | Date | null | undefined): dayjs.Dayjs {
    return parseDateInput(input)
  },

  formatDate(input: string | number | Date | null | undefined, formatStr = 'YYYY-MM-DD HH:mm'): string {
    const d = parseDateInput(input)
    if (!d.isValid()) return ''
    return d.format(formatStr)
  },

  daysToSeconds(days: number | string | null | undefined): number | undefined {
    if (days === null || days === undefined || days === '') return undefined
    const num = Number(days)
    if (!Number.isFinite(num)) return undefined
    return Math.round(num * 86400)
  },

  secondsToDays(seconds: number | string | null | undefined): number {
    const num = Number(seconds ?? 0)
    if (!Number.isFinite(num) || num <= 0) return 0
    return Math.round(num / 86400)
  },
}

export interface RelativeExpiryDateInfo {
  status: 'expires' | 'expired' | ''
  time: string
}

export function useRelativeExpiryDate(
  unixTime: number | null | undefined,
  userStatus?: UserStatus,
): RelativeExpiryDateInfo {
  const { t } = useTranslation()

  return useMemo(() => {
    if (!unixTime || !Number.isFinite(unixTime) || unixTime <= 0 || userStatus === 'on_hold') {
      return { status: '', time: '' }
    }

    const nowSec = Math.floor(Date.now() / 1000)
    const diffSec = unixTime - nowSec
    const absSec = Math.abs(diffSec)

    const days = Math.floor(absSec / 86400)
    const hours = Math.floor((absSec % 86400) / 3600)
    const minutes = Math.max(1, Math.floor((absSec % 3600) / 60))

    let formatted: string
    if (days >= 1) {
      const unit = t(`time.${days === 1 ? 'day' : 'days'}`, { defaultValue: days === 1 ? 'day' : 'days' })
      formatted = `${days} ${unit}`
    } else if (hours >= 1) {
      const unit = t(`time.${hours === 1 ? 'hour' : 'hours'}`, { defaultValue: hours === 1 ? 'hour' : 'hours' })
      formatted = `${hours} ${unit}`
    } else {
      const unit = t(`time.${minutes === 1 ? 'minute' : 'minutes'}`, { defaultValue: minutes === 1 ? 'minute' : 'minutes' })
      formatted = `${minutes} ${unit}`
    }

    return {
      status: diffSec >= 0 ? 'expires' : 'expired',
      time: formatted,
    }
  }, [unixTime, userStatus, t])
}
