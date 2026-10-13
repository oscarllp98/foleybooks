import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  getTokenSnapshot,
  markSessionExpired,
  setTokens,
  subscribeTokenChanges,
} from './tokens'

const USER = {
  id: 'u-1',
  email: 'reader@example.com',
  role: 'CUSTOMER',
} as const

describe('tokens', () => {
  afterEach(() => {
    clearTokens()
  })

  it('setTokens_whenCalled_keepsAccessTokenInMemoryOnly', () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    expect(getAccessToken()).toBe('access-1')
    expect(getRefreshToken()).toBe('refresh-1')
    const persisted = Object.keys(localStorage).map(
      (key) => localStorage.getItem(key) ?? '',
    )
    expect(persisted).not.toContain('access-1')
  })

  it('clearTokens_whenCalled_removesAccessAndRefreshTokens', () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    clearTokens()

    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('subscribeTokenChanges_whenTokensSetOrCleared_notifiesListener', () => {
    const listener = vi.fn()
    const unsubscribe = subscribeTokenChanges(listener)

    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    clearTokens()

    expect(listener).toHaveBeenCalledTimes(2)
    unsubscribe()
  })

  it('subscribeTokenChanges_whenUnsubscribed_stopsNotifying', () => {
    const listener = vi.fn()
    const unsubscribe = subscribeTokenChanges(listener)

    unsubscribe()
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    expect(listener).not.toHaveBeenCalled()
  })

  it('getTokenSnapshot_whenReadTwiceWithoutChanges_returnsCachedObject', () => {
    // useSyncExternalStore requires a stable snapshot between notifications.
    expect(getTokenSnapshot()).toBe(getTokenSnapshot())
  })

  it('setTokens_whenUserPresent_persistsIdentityWithoutTheAccessToken', () => {
    setTokens({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      user: USER,
    })

    expect(getTokenSnapshot().user).toEqual(USER)
    const persisted = Object.keys(localStorage).map(
      (key) => localStorage.getItem(key) ?? '',
    )
    expect(persisted).not.toContain('access-1')
  })

  it('clearTokens_whenCalled_removesPersistedIdentity', () => {
    setTokens({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      user: USER,
    })

    clearTokens()

    expect(getTokenSnapshot().user).toBeNull()
  })

  it('markSessionExpired_whenCalled_endsSessionAndFlagsExpiry', () => {
    setTokens({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      user: USER,
    })

    markSessionExpired()

    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(getTokenSnapshot().user).toBeNull()
    expect(getTokenSnapshot().sessionExpired).toBe(true)
  })

  it('setTokens_whenCalledAfterExpiry_clearsExpiryFlag', () => {
    markSessionExpired()

    setTokens({ accessToken: 'access-2', refreshToken: 'refresh-2' })

    expect(getTokenSnapshot().sessionExpired).toBe(false)
  })

  it('clearTokens_whenCalledAfterExpiry_keepsExpiryFlag', () => {
    // LC-07 vs FR-05: a plain logout must not erase "your session expired".
    markSessionExpired()

    clearTokens()

    expect(getTokenSnapshot().sessionExpired).toBe(true)
  })
})
