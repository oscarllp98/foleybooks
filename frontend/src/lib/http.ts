import axios, { type AxiosError } from 'axios'
import type { TokenPair } from '../types/auth'
import {
  getAccessToken,
  getRefreshToken,
  markSessionExpired,
  setTokens,
} from './tokens'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** Opt this request out of the 401 -> refresh -> retry flow. */
    skipAuthRefresh?: boolean
    /** Internal guard: a retried request is never refreshed a second time. */
    authRetryAttempted?: boolean
  }
}

const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1'

const AUTH_PATH_PREFIX = '/auth/'
const REFRESH_PATH = '/auth/refresh'
const LOGIN_PATH = '/login'

export const http = axios.create({ baseURL: BASE_URL })

export type SessionExpiredHandler = () => void

let sessionExpiredHandler: SessionExpiredHandler | null = null

// LC-07: a failed refresh ends the session. The router layer (FE-10) wires
// this to a client-side navigation; without a handler we hard-redirect.
// Returns the previously installed handler so a later installer (FE-04's
// AuthContext, then FE-10's router) can chain instead of silently dropping
// the earlier one — the slot stays single, composition happens by chaining.
export function setSessionExpiredHandler(
  handler: SessionExpiredHandler | null,
): SessionExpiredHandler | null {
  const previous = sessionExpiredHandler
  sessionExpiredHandler = handler
  return previous
}

http.interceptors.request.use((config) => {
  if (config.url?.startsWith(AUTH_PATH_PREFIX)) config.skipAuthRefresh = true
  const accessToken = getAccessToken()
  if (accessToken) config.headers.set('Authorization', `Bearer ${accessToken}`)
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const config = error.config
    if (
      !config ||
      config.skipAuthRefresh ||
      config.authRetryAttempted ||
      error.response?.status !== 401 ||
      getRefreshToken() === null
    ) {
      return Promise.reject(error)
    }
    config.authRetryAttempted = true
    const session = await refreshSession()
    if (session === null) {
      // A null session means the refresh credential is gone: performRefresh
      // has already ended the session and fired the handler (LC-07).
      return Promise.reject(error)
    }
    config.headers.set('Authorization', `Bearer ${session.accessToken}`)
    return http.request(config)
  },
)

let refreshPromise: Promise<TokenPair | null> | null = null

// LC-22: concurrent 401s share a single in-flight refresh, so the client
// never rotates twice from the same credential (a lost race = reuse).
// FE-04: this is the ONE refresh path — the AuthContext calls it rather than
// rotating the credential itself, and it returns the full TokenPair so the
// session identity can be restored after a transparent refresh (FR-04).
export function refreshSession(): Promise<TokenPair | null> {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

async function performRefresh(): Promise<TokenPair | null> {
  const refreshToken = getRefreshToken()
  if (refreshToken === null) return null
  try {
    const response = await http.post<TokenPair>(
      REFRESH_PATH,
      { refreshToken },
      { skipAuthRefresh: true },
    )
    setTokens(response.data)
    return response.data
  } catch {
    // LC-07: the credential was rejected — end the session here, the single
    // failure door shared by the 401 interceptor and AuthContext.refresh().
    onSessionExpired()
    return null
  }
}

function onSessionExpired(): void {
  markSessionExpired()
  if (sessionExpiredHandler) {
    sessionExpiredHandler()
  } else {
    window.location.assign(`${LOGIN_PATH}?reason=session-expired`)
  }
}
