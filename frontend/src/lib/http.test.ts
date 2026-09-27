import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { http, setSessionExpiredHandler } from './http'
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setTokens,
} from './tokens'

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
    respond(config, status, {
      type: 'urn:foley-books:problem:unauthenticated',
      status,
    }),
  )
}

function requestsTo(url: string): InternalAxiosRequestConfig[] {
  return calls.filter((config) => config.url === url)
}

const TOKEN_PAIR = {
  accessToken: 'access-new',
  refreshToken: 'refresh-new',
  tokenType: 'Bearer',
  expiresIn: 900,
}

beforeEach(() => {
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

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('http', () => {
  it('interceptsRequest_whenAccessTokenPresent_setsBearerAuthorizationHeader', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    await http.get('/books')

    expect(requestsTo('/books')[0].headers.get('Authorization')).toBe(
      'Bearer access-1',
    )
  })

  it('interceptsRequest_whenNoAccessToken_omitsAuthorizationHeader', async () => {
    await http.get('/books')

    expect(requestsTo('/books')[0].headers.get('Authorization')).toBeFalsy()
  })

  it('responseInterceptor_whenRequestGets401WithRefreshToken_refreshesAndRetriesOriginal', async () => {
    setTokens({ accessToken: 'access-old', refreshToken: 'refresh-old' })
    handler = (config) => {
      if (config.url === '/auth/refresh')
        return respond(config, 200, TOKEN_PAIR)
      if (config.authRetryAttempted) return respond(config, 200, { ok: true })
      return fail(config, 401)
    }

    const response = await http.get('/cart')

    expect(response.data).toEqual({ ok: true })
    expect(requestsTo('/auth/refresh')).toHaveLength(1)
    expect(JSON.parse(String(requestsTo('/auth/refresh')[0].data))).toEqual({
      refreshToken: 'refresh-old',
    })
    expect(requestsTo('/cart')[1].headers.get('Authorization')).toBe(
      'Bearer access-new',
    )
    expect(getRefreshToken()).toBe('refresh-new')
  })

  it('responseInterceptor_whenConcurrentRequestsBothGet401_performsSingleRefresh', async () => {
    setTokens({ accessToken: 'access-old', refreshToken: 'refresh-old' })
    handler = (config) => {
      if (config.url === '/auth/refresh')
        return respond(config, 200, TOKEN_PAIR)
      if (config.authRetryAttempted) return respond(config, 200, config.url)
      return fail(config, 401)
    }

    const [first, second] = await Promise.all([http.get('/a'), http.get('/b')])

    expect(first.data).toBe('/a')
    expect(second.data).toBe('/b')
    expect(requestsTo('/auth/refresh')).toHaveLength(1)
  })

  it('responseInterceptor_whenRefreshFails_clearsTokensAndTriggersSessionExpiredHandler', async () => {
    const onExpired = vi.fn()
    setSessionExpiredHandler(onExpired)
    setTokens({ accessToken: 'access-old', refreshToken: 'refresh-old' })
    handler = (config) => fail(config, 401)

    await expect(http.get('/cart')).rejects.toBeInstanceOf(AxiosError)

    expect(onExpired).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(requestsTo('/cart')).toHaveLength(1)
  })

  it('responseInterceptor_whenRefreshFailsWithoutHandler_redirectsToLoginWithSessionExpiredReason', async () => {
    const assign = vi.fn()
    vi.stubGlobal('location', { assign })
    setTokens({ accessToken: 'access-old', refreshToken: 'refresh-old' })
    handler = (config) => fail(config, 401)

    await expect(http.get('/cart')).rejects.toBeInstanceOf(AxiosError)

    expect(assign).toHaveBeenCalledWith('/login?reason=session-expired')
  })

  it('responseInterceptor_whenAnonymousRequestGets401_doesNotRefreshOrEndSession', async () => {
    const onExpired = vi.fn()
    setSessionExpiredHandler(onExpired)
    handler = (config) =>
      config.url === '/cart' ? fail(config, 401) : respond(config, 200, {})

    await expect(http.get('/cart')).rejects.toBeInstanceOf(AxiosError)

    expect(requestsTo('/auth/refresh')).toHaveLength(0)
    expect(onExpired).not.toHaveBeenCalled()
  })

  it('responseInterceptor_whenAuthEndpointGets401_skipsRefreshFlow', async () => {
    const onExpired = vi.fn()
    setSessionExpiredHandler(onExpired)
    setTokens({ accessToken: 'access-old', refreshToken: 'refresh-old' })
    handler = (config) =>
      config.url === '/auth/login'
        ? fail(config, 401)
        : respond(config, 200, {})

    await expect(
      http.post('/auth/login', { email: 'reader@example.com', password: 'x' }),
    ).rejects.toBeInstanceOf(AxiosError)

    expect(requestsTo('/auth/refresh')).toHaveLength(0)
    expect(onExpired).not.toHaveBeenCalled()
  })
})
