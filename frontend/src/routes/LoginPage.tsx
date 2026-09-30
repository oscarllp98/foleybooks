import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { LoginForm } from '../features/auth/LoginForm'
import { useAuth } from '../hooks/useAuth'
import type { LoginRedirectState } from '../types/routing'

// FE-10 (FR-03, LC-07, LC-27): the /login page. It owns the two navigation
// decisions the feature components refuse to make: where a successful login
// goes (the guard's return path, default "/") and how session expiry reads.
// The expiry notice fires from either signal — the store flag when the 401
// interceptor ended the session client-side, or the ?reason=session-expired
// search param that survives lib/http.ts's pre-wiring hard redirect
// (ADR-011), so a full reload into the fallback URL still explains itself.

const EXPIRED_COPY = {
  heading: 'Your session has expired',
  body: 'For your security we signed you out. Please sign in again to continue.',
}

export function LoginPage() {
  const { sessionExpired } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const from = (location.state as LoginRedirectState | null)?.from ?? '/'
  const showExpiredNotice =
    sessionExpired || searchParams.get('reason') === 'session-expired'

  return (
    <section className="mx-auto flex w-full max-w-md flex-col gap-4 py-4">
      {showExpiredNotice ? (
        // Guidance state, not a failure — same role="status"/amber pattern as
        // the LC-05 unverified notice: nothing the user typed was wrong.
        <div
          role="status"
          aria-labelledby="session-expired-heading"
          className="flex flex-col gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3"
        >
          <h2
            id="session-expired-heading"
            className="text-sm font-semibold text-amber-800"
          >
            {EXPIRED_COPY.heading}
          </h2>
          <p className="text-sm text-amber-700">{EXPIRED_COPY.body}</p>
        </div>
      ) : null}

      <LoginForm
        onLoggedIn={() => {
          navigate(from, { replace: true })
        }}
      />
    </section>
  )
}
