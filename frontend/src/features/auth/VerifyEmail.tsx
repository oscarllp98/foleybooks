import { isAxiosError } from 'axios'
import { useEffect, useState } from 'react'
import { confirm } from '../../api/auth'
import { ErrorState } from '../../components/ErrorState'
import { Spinner } from '../../components/Spinner'
import { confirmRequestSchema } from '../../types/auth'
import { ResendConfirmation } from './ResendConfirmation'

// FE-07 (FR-02, LC-02, LC-03): the /verify-email?token=… landing page (D-01).
// The confirmation link is a GET into the frontend carrying the token as a
// query param (never a gateway-logged path segment); this page POSTs it once
// to /auth/confirm and renders one of the contract's states:
//   CONFIRMED            → success, log-in prompt (FR-02)
//   ALREADY_CONFIRMED    → idempotent success, never a confusing error (LC-03)
//   410 + detail         → expired/invalid link + inline resend offer (LC-02)
//   anything else        → generic NFR-06 failure with traceId + retry
// A missing or malformed token never reaches the network: it renders the same
// invalid-link state directly, because the server answers unknown tokens
// uniformly — the round trip would tell the user nothing new (C24: the raw
// token never renders).

interface ConfirmProblem {
  detail?: string
  traceId?: string
}

/** Terminal outcomes of the POST /auth/confirm call; null means in flight. */
type Outcome =
  | { kind: 'confirmed'; message: string }
  | { kind: 'already-confirmed'; message: string }
  | { kind: 'link-gone'; message: string }
  | { kind: 'failure'; traceId?: string }

const INVALID_LINK_FALLBACK =
  'This confirmation link is not valid or has expired. You can request a new one.'
const GENERIC_FAILURE = 'We could not confirm your email. Please try again.'

// D-01's link target is a real URL, so reading window.location.search works
// both today and once FE-10 mounts this page under React Router (client-side
// navigation updates window.location too). No speculative prop (C1).
function tokenFromUrl(): string {
  return new URLSearchParams(window.location.search).get('token') ?? ''
}

export function VerifyEmail() {
  const presentedToken = tokenFromUrl()
  const tokenIsValid = confirmRequestSchema.safeParse({
    token: presentedToken,
  }).success
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!tokenIsValid) return
    // No cancellation flag: StrictMode's double effect (and a double-opened
    // link) POSTs the token twice, and confirm is idempotent by contract
    // (LC-03) — the second call answers ALREADY_CONFIRMED, still a success.
    confirm({ token: presentedToken })
      .then((response) => {
        setOutcome(
          response.outcome === 'CONFIRMED'
            ? { kind: 'confirmed', message: response.message }
            : { kind: 'already-confirmed', message: response.message },
        )
      })
      .catch((error: unknown) => {
        const status = isAxiosError(error) ? error.response?.status : undefined
        const problem = isAxiosError(error)
          ? ((error.response?.data as ConfirmProblem | undefined) ?? null)
          : null
        if (status === 410) {
          // The server's user-facing detail distinguishes "expired" from
          // "invalid or expired" (LC-02); resendHint is an API pointer for
          // machines, not copy — the resend form below IS the hint.
          setOutcome({
            kind: 'link-gone',
            message: problem?.detail ?? INVALID_LINK_FALLBACK,
          })
          return
        }
        setOutcome({ kind: 'failure', traceId: problem?.traceId })
      })
  }, [tokenIsValid, presentedToken, attempt])

  if (!tokenIsValid) {
    return <InvalidLink message={INVALID_LINK_FALLBACK} />
  }
  if (outcome === null) {
    return <Spinner label="Confirming your email…" />
  }

  switch (outcome.kind) {
    case 'confirmed':
      return <Success title="Email confirmed" message={outcome.message} />
    case 'already-confirmed':
      return <Success title="Already confirmed" message={outcome.message} />
    case 'link-gone':
      return <InvalidLink message={outcome.message} />
    case 'failure':
      return (
        <ErrorState
          message={GENERIC_FAILURE}
          traceId={outcome.traceId}
          onRetry={() => {
            setOutcome(null)
            setAttempt((current) => current + 1)
          }}
        />
      )
  }
}

function Success({ title, message }: { title: string; message: string }) {
  return (
    <section
      role="status"
      aria-labelledby="verify-email-heading"
      className="flex flex-col gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-6 py-8"
    >
      <h2
        id="verify-email-heading"
        className="text-lg font-semibold text-emerald-800"
      >
        {title}
      </h2>
      <p className="text-sm text-emerald-700">{message}</p>
      {/* Pre-router transitional CTA, same convention as lib/http.ts LOGIN_PATH
          and ADR-012's pre-router hard redirect: a plain <a> works standalone
          today and is swapped for a <Link> when FE-10 mounts the routes. */}
      <a
        href="/login"
        className="mt-1 self-start rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
      >
        Go to login
      </a>
    </section>
  )
}

function InvalidLink({ message }: { message: string }) {
  // LC-02: clear error + the option to request a new link, inline.
  return (
    <section
      aria-labelledby="verify-email-heading"
      className="flex flex-col gap-4"
    >
      <div className="flex flex-col gap-1">
        <h2
          id="verify-email-heading"
          className="text-lg font-semibold text-red-800"
        >
          Confirmation link is not valid
        </h2>
        <p role="alert" className="text-sm text-red-700">
          {message}
        </p>
      </div>
      <ResendConfirmation />
    </section>
  )
}
