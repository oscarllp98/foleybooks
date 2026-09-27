export interface AuthTokens {
  accessToken: string
  refreshToken: string
}

const REFRESH_TOKEN_KEY = 'foleybooks.refreshToken'

// D-13: the access token lives in memory only; the refresh credential is
// persisted so the session survives reloads within its 7-day window (FR-04).
let accessToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setTokens(tokens: AuthTokens): void {
  accessToken = tokens.accessToken
  localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
}

export function clearTokens(): void {
  accessToken = null
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}
