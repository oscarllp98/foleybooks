import {
  useCallback,
  useEffect,
  useMemo,
  useSyncExternalStore,
  type ReactNode,
} from 'react'
import { isAxiosError } from 'axios'
import * as authApi from '../../api/auth'
import { refreshSession, setSessionExpiredHandler } from '../../lib/http'
import {
  clearTokens,
  getRefreshToken,
  getTokenSnapshot,
  markSessionExpired,
  setTokens,
  subscribeTokenChanges,
} from '../../lib/tokens'
import type { LoginRequest } from '../../types/auth'
import {
  AuthContext,
  type AuthContextValue,
  type LoginResult,
} from './AuthContext'

interface AuthProviderProps {
  children: ReactNode
}

/**
 * FE-04 (ADR-011): the AuthContext wraps lib/tokens.ts — the single session
 * store — rather than keeping a parallel copy. Login/refresh/logout and the
 * interceptor's transparent rotations all settle in that store; React
 * observes them via useSyncExternalStore, so components and FE-10's guards
 * render one coherent auth state.
 */
export function AuthProvider({ children }: AuthProviderProps) {
  const snapshot = useSyncExternalStore(
    subscribeTokenChanges,
    getTokenSnapshot,
    getTokenSnapshot,
  )

  // ADR-011: wire the session-expired seam so teardown always lands in the
  // store. setSessionExpiredHandler hands back the handler it displaced, so
  // a previously installed one (FE-10's navigation) is chained, never
  // dropped; unmounting restores it.
  useEffect(() => {
    const previous = setSessionExpiredHandler(() => {
      markSessionExpired()
      previous?.()
    })
    return () => {
      setSessionExpiredHandler(previous)
    }
  }, [])

  const login = useCallback(
    async (request: LoginRequest): Promise<LoginResult> => {
      try {
        const pair = await authApi.login(request)
        setTokens(pair)
        return { ok: true, user: pair.user }
      } catch (error) {
        if (isAxiosError(error) && error.response?.status === 403) {
          return { ok: false, reason: 'unverified' }
        }
        if (isAxiosError(error) && error.response?.status === 401) {
          return { ok: false, reason: 'invalid-credentials' }
        }
        throw error
      }
    },
    [],
  )

  const logout = useCallback(async (): Promise<void> => {
    const refreshToken = getRefreshToken()
    // The local session ends first and unconditionally (FR-05): a failed
    // revoke cannot leave a half-logged-in client, and the server-side
    // credential still expires with its own window.
    clearTokens()
    if (refreshToken === null) return
    try {
      await authApi.logout({ refreshToken })
    } catch {
      // Swallowed by design; the store change above already told React.
    }
  }, [])

  const refresh = useCallback(async (): Promise<boolean> => {
    return (await refreshSession()) !== null
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user: snapshot.user,
      isAuthenticated: snapshot.refreshToken !== null,
      sessionExpired: snapshot.sessionExpired,
      login,
      logout,
      refresh,
    }),
    [snapshot, login, logout, refresh],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
