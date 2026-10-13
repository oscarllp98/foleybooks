import { http } from '../lib/http'
import type {
  ConfirmRequest,
  ConfirmResponse,
  LoginRequest,
  LogoutRequest,
  RegisterRequest,
  RegisterResponse,
  ResendRequest,
  ResendResponse,
  TokenPair,
} from '../types/auth'

// FE-03: the only place auth-service HTTP happens (C10). Thin typed wrappers
// over the shared axios instance — no state, no token storage, no redirects:
// those are the AuthContext's job (FE-04). The instance's request interceptor
// already flags /auth/ paths to skip the refresh flow (LC-22), so a failed
// login rejects straight to the caller instead of looping.
//
// POST /auth/refresh is deliberately NOT mirrored here: the ONE rotation door
// is lib/http.ts refreshSession() (performRefresh's single-flight path,
// LC-07/LC-22), shared by the 401 interceptor and — per ADR-012 — by
// useAuth().refresh(). A public client here would only offer a second,
// uncoordinated way to rotate the credential (C1).

const BASE_PATH = '/auth'

export async function register(
  request: RegisterRequest,
): Promise<RegisterResponse> {
  const { data } = await http.post<RegisterResponse>(
    `${BASE_PATH}/register`,
    request,
  )
  return data
}

export async function confirm(
  request: ConfirmRequest,
): Promise<ConfirmResponse> {
  const { data } = await http.post<ConfirmResponse>(
    `${BASE_PATH}/confirm`,
    request,
  )
  return data
}

export async function resend(request: ResendRequest): Promise<ResendResponse> {
  const { data } = await http.post<ResendResponse>(
    `${BASE_PATH}/resend`,
    request,
  )
  return data
}

export async function login(request: LoginRequest): Promise<TokenPair> {
  const { data } = await http.post<TokenPair>(`${BASE_PATH}/login`, request)
  return data
}

export async function logout(request: LogoutRequest): Promise<void> {
  await http.post<void>(`${BASE_PATH}/logout`, request)
}
