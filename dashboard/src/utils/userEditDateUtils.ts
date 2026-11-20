import { parseDateInput } from './dateTimeParsing'

export function normalizeDatePickerValueForEditForm(
  value: string | number | Date | null | undefined,
): string {
  if (value === null || value === undefined || value === '') return ''
  const parsed = parseDateInput(value)
  if (!parsed.isValid()) return ''
  return parsed.toISOString()
}
