import type { QueryClient } from '@tanstack/react-query'
import type { UserResponse, UsersResponse } from '@/service/api'

interface CacheUpsertOptions {
  allowInsert?: boolean
}

export function invalidateUserMetricsQueries(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: ['/api/system'] })
  void queryClient.invalidateQueries({ queryKey: ['/api/system/users'] })
}

export function upsertUserInUsersCache(
  queryClient: QueryClient,
  user: UserResponse,
  options?: CacheUpsertOptions,
): void {
  queryClient.setQueriesData<UsersResponse>({ queryKey: ['/api/users'] }, old => {
    if (!old || !Array.isArray(old.users)) return old
    const idx = old.users.findIndex(item => item.id === user.id || item.username === user.username)
    if (idx === -1) {
      if (!options?.allowInsert) return old
      return {
        ...old,
        users: [user, ...old.users],
        total: typeof old.total === 'number' ? old.total + 1 : old.users.length + 1,
      }
    }
    const nextUsers = [...old.users]
    nextUsers[idx] = { ...nextUsers[idx], ...user }
    return { ...old, users: nextUsers }
  })
}

export function removeUserFromUsersCache(queryClient: QueryClient, user: Pick<UserResponse, 'id'>): void {
  removeUsersFromUsersCache(queryClient, [user])
}

export function removeUsersFromUsersCache(
  queryClient: QueryClient,
  users: Array<Pick<UserResponse, 'id'>>,
): void {
  const removeIds = new Set(users.map(u => u.id))
  if (removeIds.size === 0) return

  queryClient.setQueriesData<UsersResponse>({ queryKey: ['/api/users'] }, old => {
    if (!old || !Array.isArray(old.users)) return old
    const filtered = old.users.filter(item => !removeIds.has(item.id))
    const removedCount = old.users.length - filtered.length
    if (removedCount === 0) return old
    return {
      ...old,
      users: filtered,
      total: typeof old.total === 'number' ? Math.max(0, old.total - removedCount) : filtered.length,
    }
  })
  invalidateUserMetricsQueries(queryClient)
}
