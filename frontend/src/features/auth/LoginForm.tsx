import { zodResolver } from '@hookform/resolvers/zod'
import { isAxiosError } from 'axios'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { ErrorState } from '../../components/ErrorState'
import { useAuth } from '../../hooks/useAuth'
import { loginRequestSchema, type LoginRequest } from '../../types/auth'
import { ResendConfirmation } from './ResendConfirmation'

// FE-08 (FR-03): the login form. Session state is owned by AuthProvider
// (FE-04, ADR-012) — this form only calls useAuth().login and renders the
// LoginResult it gets back; it never touches api/auth, lib/http or
// lib/tokens directly.
//
// The zod mirror here (loginRequestSchema, FE-02) is deliberately weaker than
// registration's: password is checked structurally only. Complexity or length
// feedback at login would be a shape oracle (FR-03, LC-06), so anything that
// smells like a password rule belongs to the register form, not this one.

const FIELD_NAMES = ['email', 'password'] as const

/** The login endpoint's ProblemDetail properties this form can act on. */
interface LoginProblem {
  traceId?: string
  errors?: { field: string; message: string }[]
}

type FieldName = (typeof FIELD_NAMES)[number]

function isProblemFieldName(value: string): value is FieldName {
  return (FIELD_NAMES as readonly string[]).includes(value)
}

// LC-06: one generic string for every credential failure — 401 covers wrong
// password AND unknown account (ADR-007's timing-parity branch), so the copy
// must not suggest which. Rendering it identically on retry is the test.
const INVALID_CREDENTIALS = 'The email or password is incorrect.'
// LC-05: 403 is the unverified flavor — a different status, not a different
// message, so separating them leaks nothing the server didn't already say.
const TRANSPORT_FAILURE = 'We could not sign you in. Please try again.'

/** Form-level outcome of one submission; field errors live in RHF state. */
type Notice =
  | { kind: 'unverified' }
  | { kind: 'invalid-credentials' }
  | { kind: 'transport'; traceId?: string }

function problemData(error: unknown): LoginProblem | null {
  if (!isAxiosError(error)) return null
  return (error.response?.data as LoginProblem | undefined) ?? null
}

interface LoginFormProps {
  /**
   * Called after the session exists (AuthProvider already updated). The page
   * that mounts this form owns where to go next — FE-10 wires this to the
   * post-login return path; the form navigates nothing itself (C1).
   */
  onLoggedIn?: () => void
}

export function LoginForm({ onLoggedIn }: LoginFormProps) {
  const { login } = useAuth()
  const [notice, setNotice] = useState<Notice | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginRequest>({
    // Same NFR-03 pattern as RegisterForm: inline feedback once a field is
    // done, with the zod mirror (FE-02) speaking in the backend's words.
    resolver: zodResolver(loginRequestSchema),
    mode: 'onTouched',
  })

  const onSubmit = handleSubmit(async (values) => {
    setNotice(null)
    try {
      // The resolver output is the normalized payload: email trimmed and
      // lowercased, password verbatim (FR-03, LC-15, LC-26).
      const result = await login(values)
      if (result.ok) {
        onLoggedIn?.()
        return
      }
      setNotice(
        result.reason === 'unverified'
          ? { kind: 'unverified' }
          : { kind: 'invalid-credentials' },
      )
    } catch (error) {
      const problem = problemData(error)
      const status = isAxiosError(error) ? error.response?.status : undefined
      const fieldErrors =
        status === 400
          ? (problem?.errors ?? []).filter(
              (issue): issue is { field: FieldName; message: string } =>
                isProblemFieldName(issue.field),
            )
          : []
      if (fieldErrors.length > 0) {
        // LoginRequest is structurally validated, so a 400 here means the
        // server rejected a shape zod let through; map it to the same slot.
        for (const issue of fieldErrors) {
          setError(issue.field, { type: 'server', message: issue.message })
        }
        return
      }
      // NFR-06: anything else (network, 5xx) collapses to generic + traceId.
      setNotice({ kind: 'transport', traceId: problem?.traceId })
    }
  })

  return (
    <>
      <form
        onSubmit={onSubmit}
        noValidate
        aria-label="Sign in"
        className="flex flex-col gap-4"
      >
        <h2 className="text-lg font-semibold text-neutral-900">Sign in</h2>

        <div className="flex flex-col gap-1">
          <label
            htmlFor="login-email"
            className="text-sm font-medium text-neutral-800"
          >
            Email
          </label>
          <input
            id="login-email"
            type="email"
            autoComplete="email"
            aria-invalid={errors.email !== undefined}
            aria-describedby={
              errors.email !== undefined ? 'login-email-error' : undefined
            }
            {...register('email')}
            className="rounded-md border border-neutral-300 px-3 py-2 text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-neutral-500"
          />
          {errors.email !== undefined ? (
            <p
              id="login-email-error"
              aria-live="polite"
              className="text-sm text-red-700"
            >
              {errors.email.message}
            </p>
          ) : null}
        </div>

        <div className="flex flex-col gap-1">
          <label
            htmlFor="login-password"
            className="text-sm font-medium text-neutral-800"
          >
            Password
          </label>
          <input
            id="login-password"
            type="password"
            autoComplete="current-password"
            aria-invalid={errors.password !== undefined}
            aria-describedby={
              errors.password !== undefined ? 'login-password-error' : undefined
            }
            {...register('password')}
            className="rounded-md border border-neutral-300 px-3 py-2 text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-neutral-500"
          />
          {errors.password !== undefined ? (
            <p
              id="login-password-error"
              aria-live="polite"
              className="text-sm text-red-700"
            >
              {errors.password.message}
            </p>
          ) : null}
        </div>

        {notice?.kind === 'invalid-credentials' ? (
          <p role="alert" className="text-sm font-medium text-red-700">
            {INVALID_CREDENTIALS}
          </p>
        ) : null}

        {notice?.kind === 'transport' ? (
          <ErrorState
            title="Sign-in unavailable"
            message={TRANSPORT_FAILURE}
            traceId={notice.traceId}
          />
        ) : null}

        {isSubmitting ? (
          // NFR-03/NFR-04 (FE-05 Spinner pattern): the pending state is also
          // announced, not just visible on the disabled button.
          <span role="status" aria-live="polite" className="sr-only">
            Signing in…
          </span>
        ) : null}

        <button
          type="submit"
          disabled={isSubmitting}
          aria-busy={isSubmitting}
          className="self-start rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
        >
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>

      {notice?.kind === 'unverified' ? (
        // LC-05 (FR-03): "confirm your email" — a guidance state, not a
        // failure: role="status", and no red styling, because nothing the
        // user typed was wrong. Rendered OUTSIDE the <form> because the
        // recovery panel below is a form itself, and HTML forbids nested
        // forms (FE-07's VerifyEmail composes the same panel inline).
        <section className="mt-4 flex flex-col gap-4">
          <div
            role="status"
            aria-labelledby="login-unverified-heading"
            className="flex flex-col gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3"
          >
            <h3
              id="login-unverified-heading"
              className="text-sm font-semibold text-amber-800"
            >
              Confirm your email first
            </h3>
            <p className="text-sm text-amber-700">
              We need to verify your address before you can sign in. Open the
              confirmation link in your inbox, or request a new one below.
            </p>
          </div>
          <ResendConfirmation />
        </section>
      ) : null}
    </>
  )
}
