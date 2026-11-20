export function toWholeSeconds(value: number | string | null | undefined, unitSeconds = 1): number {
  const numeric = Number(value ?? 0)
  if (!Number.isFinite(numeric) || numeric <= 0) return 0
  const factor = Number.isFinite(unitSeconds) && unitSeconds > 0 ? unitSeconds : 1
  return Math.round(numeric * factor)
}

export function toPositiveWholeSeconds(value: number | string | null | undefined, unitSeconds = 1): number {
  const seconds = toWholeSeconds(value, unitSeconds)
  return seconds > 0 ? seconds : 0
}
