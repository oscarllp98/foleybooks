import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthProvider'
import { useAuth } from '../../hooks/useAuth'
import { http, setSessionExpiredHandler } from '../../lib/http'
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setTokens,
} from '../../lib/tokens'
import type { LoginRequest } from '../../types/auth'

type Handler = (config: InternalAxiosRequestConfig) => AxiosResponse

let calls: InternalAxiosRequestConfig[]
let handler: Handler

function respond(
  config: InternalAxiosRequestConfig,
  status: number,
  data: unknown,
): AxiosResponse {
  return { status, statusText: '', headers: {}, config, data }
}

function fail(config: InternalAxiosRequestConfig, status: number): never {
  throw new AxiosError(
    `Request failed with status code ${status}`,
    AxiosError.ERR_BAD_REQUEST,
    config,
    {},
    respond(config, status, { type: 'urn:foley-books:problem:error', status }),
  )
}

function requestsTo(url: string): InternalAxiosRequestConfig[] {
  return calls.filter((config) => config.url === url)
}

const CREDENTIALS: LoginRequest = {
  email: 'reader@example.com',
  password: 'Bookworm7',
}

const TOKEN_PAIR = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: { id: 'u-1', email: 'reader@example.com', role: 'CUSTOMER' },
} as const

const ROTATED_PAIR = {
  ...TOKEN_PAIR,
  accessToken: 'access-2',
  refreshToken: 'refresh-2',
}

function wrapper({ children }: { children: ReactNode }) {
  return <AuthProvider>{children}</AuthProvider>
}

async function signIn(result: {
  current: ReturnType<typeof useAuth>
}): Promise<void> {
  handler = (config) =>
    config.url === '/auth/login'
      ? respond(config, 200, TOKEN_PAIR)
      : respond(config, 200, {})
  await act(async () => {
    await result.current.login(CREDENTIALS)
  })
}

beforeEach(() => {
  // normalizeSession: setTokens-then-clearTokens also resets the LC-07 flag
  // that clearTokens alone deliberately preserves (FR-05 vs LC-07).
  setTokens({ accessToken: 'reset', refreshToken: 'reset' })
  clearTokens()
  setSessionExpiredHandler(null)
  localStorage.clear()
  calls = []
  handler = (config) => respond(config, 200, {})
  http.defaults.adapter = async (config) => {
    calls.push(config)
    return handler(config)
  }
})

describe('AuthContext', () => {
  it('login_whenCredentialsAccepted_startsSessionWithIdentityAndMemoryOnlyAccessToken', async () => {
    handler = (config) =>
      config.url === '/auth/login'
        ? respond(config, 200, TOKEN_PAIR)
        : respond(config, 200, {})
    const { result } = renderHook(() => useAuth(), { wrapper })

    const outcome = await act(async () => result.current.login(CREDENTIALS))

    expect(outcome).toEqual({ ok: true, user: TOKEN_PAIR.user })
    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.user).toEqual(TOKEN_PAIR.user)
    expect(result.current.sessionExpired).toBe(false)
    expect(getAccessToken()).toBe('access-1')
    expect(getRefreshToken()).toBe('refresh-1')
    const persisted = Object.keys(localStorage).map(
      (key) => localStorage.getItem(key) ?? '',
    )
    expect(persisted).not.toContain('access-1')
  })

  it('login_whenCredentialsRejected_reportsInvalidCredentialsAndKeepsSessionAnonymous', async () => {
    handler = (config) => fail(config, 401)
    const { result } = renderHook(() => useAuth(), { wrapper })

    const outcome = await act(async () => result.current.login(CREDENTIALS))

    expect(outcome).toEqual({ ok: false, reason: 'invalid-credentials' })
    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.user).toBeNull()
    expect(getAccessToken()).toBeNull()
    // A failed login must never trigger the refresh flow (LC-22 contract).
    expect(requestsTo('/auth/refresh')).toHaveLength(0)
  })

  it('login_whenAccountUnverified_reportsUnverified', async () => {
    handler = (config) => fail(config, 403)
    const { result } = renderHook(() => useAuth(), { wrapper })

    const outcome = await act(async () => result.current.login(CREDENTIALS))

    expect(outcome).toEqual({ ok: false, reason: 'unverified' })
    expect(result.current.isAuthenticated).toBe(false)
  })

  it('logout_whenSessionActive_revokesCurrentCredentialAndEndsSession', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper })
    await signIn(result)
    handler = (config) => respond(config, 204, undefined)

    await act(async () => {
      await result.current.logout()
    })

    expect(requestsTo('/auth/logout')).toHaveLength(1)
    expect(JSON.parse(String(requestsTo('/auth/logout')[0].data))).toEqual({
      refreshToken: 'refresh-1',
    })
    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.user).toBeNull()
    // FR-05: an explicit logout is not a session expiry (LC-07 stays false).
    expect(result.current.sessionExpired).toBe(false)
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('logout_whenRevokeRequestFails_stillEndsLocalSession', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper })
    await signIn(result)
    handler = (config) => fail(config, 500)

    await act(async () => {
      await expect(result.current.logout()).resolves.toBeUndefined()
    })

    expect(result.current.isAuthenticated).toBe(false)
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('logout_whenCalledAnonymously_postsNothing', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper })

    await act(async () => {
      await result.current.logout()
    })

    expect(requestsTo('/auth/logout')).toHaveLength(0)
    expect(result.current.isAuthenticated).toBe(false)
  })

  it('useAuth_whenOnlyRefreshTokenSurvivedReload_reportsAuthenticated', async () => {
    // D-13/user story 4: the access token died with the previous page; the
    // session lives on through the persisted credential. A reload really
    // re-initializes the store module, so vi.resetModules + a fresh import
    // reproduces it exactly — no extra store API needed.
    localStorage.setItem('foleybooks.refreshToken', 'refresh-r')
    vi.resetModules()
    const { AuthProvider: FreshAuthProvider } = await import('./AuthProvider')
    const { useAuth: freshUseAuth } = await import('../../hooks/useAuth')
    const freshWrapper = ({ children }: { children: ReactNode }) => (
      <FreshAuthProvider>{children}</FreshAuthProvider>
    )

    const { result } = renderHook(() => freshUseAuth(), {
      wrapper: freshWrapper,
    })

    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.user).toBeNull()
  })

  it('refresh_whenCredentialRotates_updatesTokensAndKeepsIdentity', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper })
    await signIn(result)
    handler = (config) =>
      config.url === '/auth/refresh'
        ? respond(config, 200, ROTATED_PAIR)
        : fail(config, 401)

    const ok = await act(async () => result.current.refresh())

    expect(ok).toBe(true)
    expect(getAccessToken()).toBe('access-2')
    expect(getRefreshToken()).toBe('refresh-2')
    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.user).toEqual(TOKEN_PAIR.user)
  })

  it('refresh_whenCredentialRejected_endsSessionAndFlagsExpiry', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper })
    await signIn(result)
    handler = (config) => fail(config, 401)

    const ok = await act(async () => result.current.refresh())

    expect(ok).toBe(false)
    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.sessionExpired).toBe(true)
    expect(result.current.user).toBeNull()
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('interceptor_whenSessionRequestGetsUnrecoverable401_endsSessionAndChainsRouterHandler', async () => {
    // FE-04 + ADR-011 chaining: a handler installed before the provider
    // (FE-10's navigation) survives the provider's wiring instead of being
    // dropped, and the auth state settles exactly once.
    const onExpired = vi.fn()
    setSessionExpiredHandler(onExpired)
    const { result } = renderHook(() => useAuth(), { wrapper })
    await signIn(result)
    handler = (config) => fail(config, 401)

    await act(async () => {
      await expect(http.get('/cart')).rejects.toBeInstanceOf(AxiosError)
    })

    expect(onExpired).toHaveBeenCalledTimes(1)
    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.sessionExpired).toBe(true)
    expect(result.current.user).toBeNull()
  })

  it('authProvider_onUnmount_restoresPreviouslyInstalledSessionExpiredHandler', () => {
    const onExpired = vi.fn()
    setSessionExpiredHandler(onExpired)
    const { unmount } = renderHook(() => useAuth(), { wrapper })

    unmount()

    // The slot must hold FE-10's handler again, not the provider's wrapper.
    const installed = setSessionExpiredHandler(null)
    expect(installed).toBe(onExpired)
  })

  it('useAuth_whenUsedWithoutProvider_throws', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    expect(() => renderHook(() => useAuth())).toThrow(/AuthProvider/)

    spy.mockRestore()
  })
})
