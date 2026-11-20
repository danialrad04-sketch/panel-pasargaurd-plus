import type { TFunction } from 'i18next'

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

export function translateHtml(
  t: TFunction,
  key: string,
  options?: Record<string, unknown>,
): string {
  if (!options) {
    return String(t(key))
  }
  const safeOptions: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(options)) {
    if (typeof v === 'string' && k !== 'defaultValue') {
      safeOptions[k] = escapeHtml(v)
    } else {
      safeOptions[k] = v
    }
  }
  return String(t(key, safeOptions))
}
