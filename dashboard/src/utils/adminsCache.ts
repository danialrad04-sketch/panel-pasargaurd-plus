import type { QueryClient } from '@tanstack/react-query'
import type { AdminDetails, AdminsResponse } from '@/service/api'

interface CacheUpsertOptions {
  allowInsert?: boolean
}

export function upsertAdminInAdminsCache(
  queryClient: QueryClient,
  admin: AdminDetails,
  options?: CacheUpsertOptions,
): void {
  queryClient.setQueriesData<AdminsResponse>({ queryKey: ['/api/admins'] }, old => {
    if (!old || !Array.isArray(old.admins)) return old
    const idx = old.admins.findIndex(
      item => (admin.id != null && item.id === admin.id) || item.username === admin.username,
    )
    if (idx === -1) {
      if (!options?.allowInsert) return old
      return {
        ...old,
        admins: [admin, ...old.admins],
        total: typeof old.total === 'number' ? old.total + 1 : old.admins.length + 1,
      }
    }
    const nextAdmins = [...old.admins]
    nextAdmins[idx] = { ...nextAdmins[idx], ...admin }
    return { ...old, admins: nextAdmins }
  })
}

export function patchAdminInAdminsCache(
  queryClient: QueryClient,
  adminId: number,
  patch: Partial<AdminDetails>,
): void {
  queryClient.setQueriesData<AdminsResponse>({ queryKey: ['/api/admins'] }, old => {
    if (!old || !Array.isArray(old.admins)) return old
    const idx = old.admins.findIndex(item => item.id === adminId)
    if (idx === -1) return old
    const nextAdmins = [...old.admins]
    nextAdmins[idx] = { ...nextAdmins[idx], ...patch }
    return { ...old, admins: nextAdmins }
  })
}

export function removeAdminFromAdminsCache(queryClient: QueryClient, adminId: number): void {
  queryClient.setQueriesData<AdminsResponse>({ queryKey: ['/api/admins'] }, old => {
    if (!old || !Array.isArray(old.admins)) return old
    const filtered = old.admins.filter(item => item.id !== adminId)
    if (filtered.length === old.admins.length) return old
    return {
      ...old,
      admins: filtered,
      total: typeof old.total === 'number' ? Math.max(0, old.total - 1) : filtered.length,
    }
  })
}
