import axios, { type AxiosError } from 'axios'
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setTokens,
  type AuthTokens,
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

type SessionExpiredHandler = () => void

let sessionExpiredHandler: SessionExpiredHandler | null = null

// LC-07: a failed refresh ends the session. The router layer (FE-10) wires
// this to a client-side navigation; without a handler we hard-redirect.
export function setSessionExpiredHandler(
  handler: SessionExpiredHandler | null,
): void {
  sessionExpiredHandler = handler
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
    const accessToken = await refreshSession()
    if (accessToken === null) {
      onSessionExpired()
      return Promise.reject(error)
    }
    config.headers.set('Authorization', `Bearer ${accessToken}`)
    return http.request(config)
  },
)

let refreshPromise: Promise<string | null> | null = null

// LC-22: concurrent 401s share a single in-flight refresh, so the client
// never rotates twice from the same credential (a lost race = reuse).
function refreshSession(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

async function performRefresh(): Promise<string | null> {
  const refreshToken = getRefreshToken()
  if (refreshToken === null) return null
  try {
    const response = await http.post<AuthTokens>(
      REFRESH_PATH,
      { refreshToken },
      { skipAuthRefresh: true },
    )
    setTokens(response.data)
    return response.data.accessToken
  } catch {
    return null
  }
}

function onSessionExpired(): void {
  clearTokens()
  if (sessionExpiredHandler) {
    sessionExpiredHandler()
  } else {
    window.location.assign(`${LOGIN_PATH}?reason=session-expired`)
  }
}
