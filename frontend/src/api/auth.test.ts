import { beforeEach, describe, expect, it } from 'vitest'
import * as authApi from './auth'
import { clearTokens } from '../lib/tokens'
import type { RecordedRequest } from '../test/recordingAdapter'
import { installRecordingAdapter } from '../test/recordingAdapter'

const TOKEN_PAIR = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: { id: 'u-1', email: 'reader@example.com', role: 'CUSTOMER' },
} as const

describe('api/auth', () => {
  let requests: RecordedRequest[]

  beforeEach(() => {
    clearTokens()
    requests = installRecordingAdapter(() => [200, TOKEN_PAIR])
  })

  it('register_postsRegisterPayload_returnsResponseBody', async () => {
    const response = {
      email: 'reader@example.com',
      status: 'UNVERIFIED',
      message: 'Confirmation is on its way.',
    }
    requests = installRecordingAdapter(() => [201, response])

    const result = await authApi.register({
      email: 'reader@example.com',
      password: 'Bookworm7',
    })

    expect(requests[0]).toMatchObject({
      method: 'POST',
      url: '/auth/register',
      body: { email: 'reader@example.com', password: 'Bookworm7' },
    })
    expect(result).toEqual(response)
  })

  it('confirm_postsToken_returnsOutcomeBody', async () => {
    const response = {
      outcome: 'CONFIRMED',
      message: 'Email confirmed. You can log in now.',
    }
    requests = installRecordingAdapter(() => [200, response])

    const result = await authApi.confirm({ token: 'abc123' })

    expect(requests[0]).toMatchObject({
      method: 'POST',
      url: '/auth/confirm',
      body: { token: 'abc123' },
    })
    expect(result).toEqual(response)
  })

  it('resend_postsEmail_returnsMessageBody', async () => {
    const response = {
      message: 'If that address is registered, a link is on its way.',
    }
    requests = installRecordingAdapter(() => [202, response])

    const result = await authApi.resend({ email: 'reader@example.com' })

    expect(requests[0]).toMatchObject({
      method: 'POST',
      url: '/auth/resend',
      body: { email: 'reader@example.com' },
    })
    expect(result).toEqual(response)
  })

  it('login_postsCredentials_returnsTokenPair', async () => {
    const result = await authApi.login({
      email: 'reader@example.com',
      password: 'Bookworm7',
    })

    expect(requests[0]).toMatchObject({
      method: 'POST',
      url: '/auth/login',
      body: { email: 'reader@example.com', password: 'Bookworm7' },
    })
    expect(result).toEqual(TOKEN_PAIR)
  })

  it('logout_postsRefreshToken_resolvesVoid', async () => {
    requests = installRecordingAdapter(() => [204, undefined])

    const result = await authApi.logout({ refreshToken: 'refresh-1' })

    expect(requests[0]).toMatchObject({
      method: 'POST',
      url: '/auth/logout',
      body: { refreshToken: 'refresh-1' },
    })
    expect(result).toBeUndefined()
  })
})
