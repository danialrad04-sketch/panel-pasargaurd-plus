const AUTH_TOKEN_KEY = 'token'
const LEGACY_TOKEN_KEY = 'auth_token'

export function getAuthToken(): string | null {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage.getItem(AUTH_TOKEN_KEY) || window.localStorage.getItem(LEGACY_TOKEN_KEY)
  } catch {
    return null
  }
}

export function setAuthToken(token: string): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(AUTH_TOKEN_KEY, token)
    window.localStorage.removeItem(LEGACY_TOKEN_KEY)
  } catch {
    // Ignore storage quota errors
  }
}

export function removeAuthToken(): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.removeItem(AUTH_TOKEN_KEY)
    window.localStorage.removeItem(LEGACY_TOKEN_KEY)
  } catch {
    // Ignore storage errors
  }
}
