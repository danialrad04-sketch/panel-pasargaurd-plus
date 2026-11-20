export type RowSelectionMap = Record<string, boolean>

export function getSelectedRowIds(selection: RowSelectionMap): number[] {
  return Object.entries(selection)
    .filter(([, selected]) => Boolean(selected))
    .map(([id]) => Number(id))
    .filter(id => Number.isFinite(id))
}

export function nextRowSelection(
  prev: RowSelectionMap,
  visibleRowIds: string[],
  reset = false,
): RowSelectionMap {
  if (reset) {
    return Object.keys(prev).length === 0 ? prev : {}
  }

  const visibleSet = new Set(visibleRowIds)
  const prevKeys = Object.keys(prev)
  let changed = false
  const next: RowSelectionMap = {}

  for (const key of prevKeys) {
    if (prev[key] && visibleSet.has(key)) {
      next[key] = true
    } else {
      changed = true
    }
  }

  return changed ? next : prev
}
