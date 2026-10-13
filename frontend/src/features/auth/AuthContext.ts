import { createContext } from 'react'
import type { LoginRequest, UserSummary } from '../../types/auth'

/**
 * FR-03 outcome for the LoginForm: the two rejection flavors are already
 * enumeration-safe server-side (LC-05/LC-06), so classifying by status —
 * not by message — leaks nothing extra.
 */
export type LoginResult =
  | { ok: true; user: UserSummary }
  | { ok: false; reason: 'invalid-credentials' | 'unverified' }

export interface AuthContextValue {
  /**
   * Identity from the latest TokenPair; persisted next to the refresh
   * credential so a reload shows the signed-in state without rotating the
   * single-use token (ADR-011 amendment). Display data only — the access
   * JWT itself lives memory-only in lib/tokens.ts (D-13).
   */
  user: UserSummary | null
  /**
   * True while a live session credential exists. Derived from the refresh
   * token, not the in-memory access token: after a reload the session is
   * still alive (FR-04, user story 4) and the interceptor restores the
   * access token on the first 401 (LC-07/LC-22).
   */
  isAuthenticated: boolean
  /**
   * LC-07: the refresh credential died mid-session. FE-10 renders the
   * "session expired, sign in again" notice from this flag.
   */
  sessionExpired: boolean
  /**
   * FR-03: email + password for a session. 401/403 are reported via
   * LoginResult; transport-level failures (no response) still throw.
   */
  login: (request: LoginRequest) => Promise<LoginResult>
  /** FR-05: revokes the current session and ends the local one (LC-09, LC-21). */
  logout: () => Promise<void>
  /**
   * FR-04: rotate through the interceptor's single-flight refresh path —
   * the ONE rotation door (LC-22). False when the session ended instead;
   * the expired flag and the session-expired handler have already fired.
   */
  refresh: () => Promise<boolean>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
