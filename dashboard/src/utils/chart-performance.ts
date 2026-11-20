export const CHART_AREA_SUGGESTION_POINT_LIMIT = 48

export interface ChartRenderFlags {
  isAnimationActive: boolean
  usePerBarRadius: boolean
  useAccessibilityLayer: boolean
  areaCurveType: 'monotone' | 'linear'
}

export function getChartRenderFlags(dataLength: number, seriesCount = 1): ChartRenderFlags {
  const totalPoints = Math.max(0, dataLength) * Math.max(1, seriesCount)
  const dense = dataLength > 60 || totalPoints > 180
  return {
    isAnimationActive: !dense,
    usePerBarRadius: dataLength <= 45,
    useAccessibilityLayer: dataLength <= 80,
    areaCurveType: dense ? 'linear' : 'monotone',
  }
}

export interface ChartBrushWindow {
  startIndex: number
  endIndex: number
}

export function getChartBrushWindow(
  dataLength: number,
  seriesCount = 1,
): ChartBrushWindow | null {
  if (dataLength <= 24) return null
  const windowSize = seriesCount > 4 ? 18 : 24
  const endIndex = Math.max(0, dataLength - 1)
  const startIndex = Math.max(0, dataLength - windowSize)
  return { startIndex, endIndex }
}
