import { zodResolver } from '@hookform/resolvers/zod'
import { isAxiosError } from 'axios'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { register as registerApi } from '../../api/auth'
import { ErrorState } from '../../components/ErrorState'
import {
  registerRequestSchema,
  type RegisterRequest,
  type RegisterResponse,
} from '../../types/auth'

// FE-06 (FR-01): registration form. The zod schema from types/auth (FE-02)
// is the single validation mirror — inline errors read exactly like the
// backend's 400 ProblemDetail `errors[]`, because they ARE the same strings.
// HTTP goes exclusively through api/auth (C10).
//
// FR-01/LC-01: a duplicate email answers the byte-identical 201, so this
// form has exactly one success state — "check your inbox" — whether the
// account is brand new or already existed. Nothing here may hint at which.

const FIELD_NAMES = ['email', 'password'] as const

/** The register endpoint's ProblemDetail properties this form can act on. */
interface RegisterProblem {
  traceId?: string
  errors?: { field: string; message: string }[]
}

type FieldName = (typeof FIELD_NAMES)[number]

function isProblemFieldName(value: string): value is FieldName {
  return (FIELD_NAMES as readonly string[]).includes(value)
}

const GENERIC_FAILURE = 'We could not create your account. Please try again.'

function problemData(error: unknown): RegisterProblem | null {
  if (!isAxiosError(error)) return null
  return (error.response?.data as RegisterProblem | undefined) ?? null
}

/**
 * FR-01/LC-18: delivery problems and duplicate branches never block or
 * distinguish; the only states are the form (with inline errors) and the
 * confirmation panel.
 */
export function RegisterForm() {
  const [success, setSuccess] = useState<RegisterResponse | null>(null)
  const [formFailure, setFormFailure] = useState<{
    message: string
    traceId?: string
  } | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterRequest>({
    // mode onTouched: inline feedback as soon as a field is done (NFR-03),
    // not while the user is still typing their first character.
    resolver: zodResolver(registerRequestSchema),
    mode: 'onTouched',
  })

  const onSubmit = handleSubmit(async (values) => {
    setFormFailure(null)
    try {
      // The resolver output is the normalized payload: email trimmed and
      // lowercased, password verbatim (LC-15, LC-26).
      setSuccess(await registerApi(values))
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
        // A field the zod mirror flagged stays server-authoritative: map the
        // ProblemDetail message onto the same inline slot.
        for (const issue of fieldErrors) {
          setError(issue.field, { type: 'server', message: issue.message })
        }
        return
      }
      // NFR-06: everything else collapses to a generic message + traceId.
      setFormFailure({ message: GENERIC_FAILURE, traceId: problem?.traceId })
    }
  })

  if (success !== null) {
    return (
      <section
        role="status"
        aria-labelledby="register-success-heading"
        className="flex flex-col gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-6 py-8"
      >
        <h2
          id="register-success-heading"
          className="text-lg font-semibold text-emerald-800"
        >
          Check your inbox
        </h2>
        <p className="text-sm text-emerald-700">{success.message}</p>
        <p className="text-sm text-emerald-700">
          We sent a confirmation link to{' '}
          <strong className="font-medium">{success.email}</strong>. The link
          stays valid for 24 hours.
        </p>
      </section>
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <h2 className="text-lg font-semibold text-neutral-900">
        Create your account
      </h2>

      <div className="flex flex-col gap-1">
        <label
          htmlFor="register-email"
          className="text-sm font-medium text-neutral-800"
        >
          Email
        </label>
        <input
          id="register-email"
          type="email"
          autoComplete="email"
          aria-invalid={errors.email !== undefined}
          aria-describedby={
            errors.email !== undefined ? 'register-email-error' : undefined
          }
          {...register('email')}
          className="rounded-md border border-neutral-300 px-3 py-2 text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-neutral-500"
        />
        {errors.email !== undefined ? (
          <p
            id="register-email-error"
            aria-live="polite"
            className="text-sm text-red-700"
          >
            {errors.email.message}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-1">
        <label
          htmlFor="register-password"
          className="text-sm font-medium text-neutral-800"
        >
          Password
        </label>
        <input
          id="register-password"
          type="password"
          autoComplete="new-password"
          aria-invalid={errors.password !== undefined}
          aria-describedby={
            errors.password !== undefined
              ? 'register-password-error'
              : undefined
          }
          {...register('password')}
          className="rounded-md border border-neutral-300 px-3 py-2 text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-neutral-500"
        />
        {errors.password !== undefined ? (
          <p
            id="register-password-error"
            aria-live="polite"
            className="text-sm text-red-700"
          >
            {errors.password.message}
          </p>
        ) : null}
      </div>

      {formFailure !== null ? (
        <ErrorState
          message={formFailure.message}
          traceId={formFailure.traceId}
        />
      ) : null}

      {isSubmitting ? (
        // NFR-03/NFR-04 (FE-05 Spinner pattern): the pending state is also
        // announced, not just visible on the disabled button.
        <span role="status" aria-live="polite" className="sr-only">
          Creating account…
        </span>
      ) : null}

      <button
        type="submit"
        disabled={isSubmitting}
        aria-busy={isSubmitting}
        className="self-start rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
      >
        {isSubmitting ? 'Creating account…' : 'Create account'}
      </button>
    </form>
  )
}
