import { lazy, type ComponentType, type LazyExoticComponent } from 'react'

const CHUNK_RELOAD_KEY = 'pg-chunk-reload-attempted'

function isChunkLoadFailure(error: unknown): boolean {
  const msg = error instanceof Error ? error.message : String(error ?? '')
  return (
    msg.includes('Failed to fetch dynamically imported module') ||
    msg.includes('Importing a module script failed') ||
    msg.includes('ChunkLoadError') ||
    msg.includes('Loading chunk')
  )
}

export function installChunkLoadRecovery(): void {
  if (typeof window === 'undefined') return
  window.addEventListener('vite:preloadError', event => {
    event.preventDefault()
    try {
      const alreadyReloaded = window.sessionStorage.getItem(CHUNK_RELOAD_KEY)
      if (!alreadyReloaded) {
        window.sessionStorage.setItem(CHUNK_RELOAD_KEY, '1')
        window.location.reload()
      }
    } catch {
      window.location.reload()
    }
  })
}

export function lazyWithChunkRecovery<T extends ComponentType<any>>(
  factory: () => Promise<{ default: T }>,
): LazyExoticComponent<T> {
  return lazy(async () => {
    try {
      const mod = await factory()
      if (typeof window !== 'undefined') {
        try {
          window.sessionStorage.removeItem(CHUNK_RELOAD_KEY)
        } catch {
          // ignore
        }
      }
      return mod
    } catch (error) {
      if (typeof window !== 'undefined' && isChunkLoadFailure(error)) {
        try {
          const alreadyReloaded = window.sessionStorage.getItem(CHUNK_RELOAD_KEY)
          if (!alreadyReloaded) {
            window.sessionStorage.setItem(CHUNK_RELOAD_KEY, '1')
            window.location.reload()
          }
        } catch {
          // ignore
        }
      }
      throw error
    }
  })
}
