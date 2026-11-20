export function getErrorStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object') return undefined
  const err = error as {
    status?: number
    statusCode?: number
    response?: { status?: number }
    cause?: { status?: number; statusCode?: number }
  }
  const candidate = err.statusCode ?? err.status ?? err.response?.status ?? err.cause?.statusCode ?? err.cause?.status
  return typeof candidate === 'number' && Number.isFinite(candidate) ? candidate : undefined
}

export function getErrorMessage(error: unknown): string {
  if (error == null) return 'Unknown error'
  if (typeof error === 'string') return error
  if (typeof error === 'object') {
    const err = error as {
      data?: { detail?: unknown; message?: unknown; error?: unknown }
      response?: { _data?: { detail?: unknown; message?: unknown; error?: unknown } }
      message?: unknown
    }
    const detail = err.data?.detail ?? err.response?._data?.detail
    if (typeof detail === 'string' && detail.trim()) return detail
    if (Array.isArray(detail) && detail.length > 0) {
      return detail
        .map(item => (typeof item === 'object' && item && 'msg' in item ? String((item as { msg: unknown }).msg) : String(item)))
        .join(', ')
    }
    const dataMsg = err.data?.message ?? err.data?.error ?? err.response?._data?.message ?? err.response?._data?.error
    if (typeof dataMsg === 'string' && dataMsg.trim()) return dataMsg
    if (typeof err.message === 'string' && err.message.trim()) return err.message
  }
  return String(error)
}

export function getErrorStack(error: unknown): string | undefined {
  if (error instanceof Error && error.stack) return error.stack
  if (error && typeof error === 'object' && 'stack' in error && typeof (error as { stack?: unknown }).stack === 'string') {
    return (error as { stack: string }).stack
  }
  return undefined
}

export function getSerializableError(error: unknown): Record<string, unknown> {
  const status = getErrorStatus(error)
  const message = getErrorMessage(error)
  const stack = getErrorStack(error)
  return {
    ...(status !== undefined ? { status } : {}),
    message,
    ...(stack ? { stack } : {}),
    ...(error && typeof error === 'object' && 'name' in error ? { name: String((error as { name: unknown }).name) } : {}),
  }
}

export function isUnauthorizedError(error: unknown): boolean {
  return getErrorStatus(error) === 401
}

export function isAuthenticationError(error: unknown): boolean {
  const status = getErrorStatus(error)
  return status === 401 || status === 403
}
