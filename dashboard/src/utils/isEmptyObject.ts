export function isEmptyObject(value: unknown): boolean {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) return true
  return Object.keys(value).length === 0
}
