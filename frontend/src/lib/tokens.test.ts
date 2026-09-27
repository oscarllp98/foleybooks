import { afterEach, describe, expect, it } from 'vitest'
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setTokens,
} from './tokens'

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
})
