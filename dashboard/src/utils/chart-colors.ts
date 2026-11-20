const LIGHT_SERIES_COLORS = [
  '#0f766e',
  '#d97706',
  '#2563eb',
  '#db2777',
  '#059669',
  '#7c3aed',
  '#ea580c',
  '#0284c7',
  '#65a30d',
  '#dc2626',
]

const DARK_SERIES_COLORS = [
  '#2dd4bf',
  '#fbbf24',
  '#60a5fa',
  '#f472b6',
  '#34d399',
  '#a78bfa',
  '#fb923c',
  '#38bdf8',
  '#a3e635',
  '#f87171',
]

export function getChartSeriesColor(index: number, isDark = false): string {
  const palette = isDark ? DARK_SERIES_COLORS : LIGHT_SERIES_COLORS
  const normalized = Math.abs(Math.floor(index)) % palette.length
  return palette[normalized]
}
