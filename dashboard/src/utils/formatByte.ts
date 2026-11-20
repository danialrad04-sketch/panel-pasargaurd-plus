const BYTES_PER_KB = 1024
const BYTES_PER_GB = 1024 * 1024 * 1024
const SIZE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'] as const

export function formatBytes(
  bytes: number | null | undefined,
  decimals = 2,
  includeUnit = true,
  _usePersianNumerals = false,
  forceUnit?: (typeof SIZE_UNITS)[number],
): string {
  const num = Number(bytes ?? 0)
  if (!Number.isFinite(num) || num <= 0) {
    return includeUnit ? `0 ${forceUnit ?? 'B'}` : '0'
  }
  const dm = decimals < 0 ? 0 : decimals
  const forcedIdx = forceUnit ? SIZE_UNITS.indexOf(forceUnit) : -1
  const i =
    forcedIdx !== -1
      ? forcedIdx
      : Math.min(Math.floor(Math.log(num) / Math.log(BYTES_PER_KB)), SIZE_UNITS.length - 1)
  const val = num / Math.pow(BYTES_PER_KB, i)
  const formatted = String(parseFloat(val.toFixed(dm)))
  return includeUnit ? `${formatted} ${SIZE_UNITS[i]}` : formatted
}

export function formatGigabytes(gb: number | null | undefined, decimals = 2): string {
  const num = Number(gb ?? 0)
  if (!Number.isFinite(num) || num <= 0) return '0 GB'
  return formatBytes(num * BYTES_PER_GB, decimals)
}

export function gbToBytes(gb: number | string | null | undefined): number {
  const num = Number(gb ?? 0)
  if (!Number.isFinite(num) || num <= 0) return 0
  return Math.round(num * BYTES_PER_GB)
}

export function bytesToFormGigabytes(bytes: number | string | null | undefined): number {
  const num = Number(bytes ?? 0)
  if (!Number.isFinite(num) || num <= 0) return 0
  return parseFloat((num / BYTES_PER_GB).toFixed(4))
}

export function formGigabytesToBytes(gb: number | string | null | undefined, originalBytes?: number | null): number {
  const num = Number(gb ?? 0)
  if (!Number.isFinite(num) || num <= 0) return 0
  if (originalBytes != null && Number.isFinite(originalBytes) && bytesToFormGigabytes(originalBytes) === num) {
    return originalBytes
  }
  return Math.round(num * BYTES_PER_GB)
}

export function numberWithCommas(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '0'
  const num = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(num)) return String(value)
  return num.toLocaleString('en-US')
}

const PERSIAN_DIGITS = ['۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹']

export function toPersianNumerals(value: string | number): string {
  return String(value).replace(/\d/g, d => PERSIAN_DIGITS[Number(d)] ?? d)
}
