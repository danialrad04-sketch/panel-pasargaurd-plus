export type ChartViewType = 'bar' | 'area'
export type DatePickerPreference = 'locale' | 'gregorian' | 'persian'

export const CHART_VIEW_TYPE_CHANGE_EVENT = 'pg-chart-view-type-change'
export const DATE_PICKER_PREFERENCE_KEY = 'pg-date-picker-preference'
export const CORE_EDITOR_VIEW_MODE_STORAGE_KEY = 'pg-core-editor-view-mode'
export const DEFAULT_CORE_EDITOR_VIEW_MODE = 'list' as const

const CHART_VIEW_TYPE_KEY = 'pg-chart-view-type'
const CORES_USE_CONFIG_MODAL_KEY = 'pg-cores-use-config-modal'
const USERS_PER_PAGE_KEY = 'pg-users-per-page'
const ADMINS_PER_PAGE_KEY = 'pg-admins-per-page'
const USERS_AUTO_REFRESH_KEY = 'pg-users-auto-refresh-seconds'

function safeGetItem(key: string): string | null {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

function safeSetItem(key: string, value: string): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // ignore storage errors
  }
}

export function getChartViewTypePreference(): ChartViewType {
  const raw = safeGetItem(CHART_VIEW_TYPE_KEY)
  return raw === 'area' ? 'area' : 'bar'
}

export function setChartViewTypePreference(viewType: ChartViewType): void {
  safeSetItem(CHART_VIEW_TYPE_KEY, viewType)
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(CHART_VIEW_TYPE_CHANGE_EVENT, { detail: viewType }))
  }
}

export function getDatePickerPreference(): DatePickerPreference {
  const raw = safeGetItem(DATE_PICKER_PREFERENCE_KEY)
  if (raw === 'gregorian' || raw === 'persian' || raw === 'locale') return raw
  return 'locale'
}

export function setDatePickerPreference(pref: DatePickerPreference): void {
  safeSetItem(DATE_PICKER_PREFERENCE_KEY, pref)
}

export function getCoresListUseConfigModal(): boolean {
  return safeGetItem(CORES_USE_CONFIG_MODAL_KEY) === 'true'
}

export function setCoresListUseConfigModal(enabled: boolean): void {
  safeSetItem(CORES_USE_CONFIG_MODAL_KEY, String(enabled))
}

export function getUsersPerPageLimitSize(): number {
  const raw = Number(safeGetItem(USERS_PER_PAGE_KEY))
  return Number.isFinite(raw) && raw > 0 ? raw : 10
}

export function setUsersPerPageLimitSize(size: number | string): void {
  const num = Number(size)
  if (Number.isFinite(num) && num > 0) {
    safeSetItem(USERS_PER_PAGE_KEY, String(num))
  }
}

export function getAdminsPerPageLimitSize(): number {
  const raw = Number(safeGetItem(ADMINS_PER_PAGE_KEY))
  return Number.isFinite(raw) && raw > 0 ? raw : 10
}

export function setAdminsPerPageLimitSize(size: number | string): void {
  const num = Number(size)
  if (Number.isFinite(num) && num > 0) {
    safeSetItem(ADMINS_PER_PAGE_KEY, String(num))
  }
}

export function getUsersAutoRefreshIntervalSeconds(): number {
  const raw = Number(safeGetItem(USERS_AUTO_REFRESH_KEY))
  return Number.isFinite(raw) && raw >= 0 ? raw : 0
}

export function setUsersAutoRefreshIntervalSeconds(seconds: number): void {
  if (Number.isFinite(seconds) && seconds >= 0) {
    safeSetItem(USERS_AUTO_REFRESH_KEY, String(seconds))
  }
}
