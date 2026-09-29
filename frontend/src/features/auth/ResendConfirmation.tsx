import { zodResolver } from '@hookform/resolvers/zod'
import { isAxiosError } from 'axios'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { resend as resendApi } from '../../api/auth'
import { ErrorState } from '../../components/ErrorState'
import {
  resendRequestSchema,
  type ResendRequest,
  type ResendResponse,
} from '../../types/auth'

// FE-07 (FR-02, LC-04): the resend-confirmation form. Verification is the
// one action an unverified user must be able to take without ever logging
// in (FR-02), so this panel is anonymous by construction: email in, fixed
// 202 out.
//
// LC-01's sibling rule for resend (LC-20): every branch — unknown address,
// already-verified, throttled, freshly-sent — answers the SAME 202 with the
// SAME generic message. So there is exactly one success state here, and it
// may never hint at which branch ran. LC-04's "wait a minute" guidance
// rides that constant server copy (the throttle answer is byte-identical on
// purpose: a throttle-specific reply would be an existence oracle) — there
// is deliberately no client-side countdown timer to add.

/** The resend endpoint's ProblemDetail properties this form can act on. */
interface ResendProblem {
  traceId?: string
  errors?: { field: string; message: string }[]
}

const GENERIC_FAILURE = 'We could not send a new link. Please try again.'

function problemData(error: unknown): ResendProblem | null {
  if (!isAxiosError(error)) return null
  return (error.response?.data as ResendProblem | undefined) ?? null
}

export function ResendConfirmation() {
  const [success, setSuccess] = useState<ResendResponse | null>(null)
  const [formFailure, setFormFailure] = useState<{
    message: string
    traceId?: string
  } | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ResendRequest>({
    // Same NFR-03 pattern as RegisterForm: inline feedback once a field is
    // done, with the zod mirror (FE-02) speaking in the backend's words.
    resolver: zodResolver(resendRequestSchema),
    mode: 'onTouched',
  })

  const onSubmit = handleSubmit(async (values) => {
    setFormFailure(null)
    try {
      // The resolver output is the normalized payload (email trimmed and
      // lowercased, LC-15's rule). Whatever comes back is displayed verbatim:
      // every 202 body carries the same generic message (LC-20).
      setSuccess(await resendApi(values))
    } catch (error) {
      const problem = problemData(error)
      const status = isAxiosError(error) ? error.response?.status : undefined
      const emailIssue =
        status === 400
          ? (problem?.errors ?? []).find((issue) => issue.field === 'email')
          : undefined
      if (emailIssue !== undefined) {
        setError('email', { type: 'server', message: emailIssue.message })
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
        aria-labelledby="resend-success-heading"
        className="flex flex-col gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-6 py-8"
      >
        <h2
          id="resend-success-heading"
          className="text-lg font-semibold text-emerald-800"
        >
          Check your inbox
        </h2>
        <p className="text-sm text-emerald-700">{success.message}</p>
      </section>
    )
  }

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      aria-label="Request a new confirmation link"
      className="flex flex-col gap-4"
    >
      <h2 className="text-lg font-semibold text-neutral-900">
        Request a new confirmation link
      </h2>

      <div className="flex flex-col gap-1">
        <label
          htmlFor="resend-email"
          className="text-sm font-medium text-neutral-800"
        >
          Email
        </label>
        <input
          id="resend-email"
          type="email"
          autoComplete="email"
          aria-invalid={errors.email !== undefined}
          aria-describedby={
            errors.email !== undefined ? 'resend-email-error' : undefined
          }
          {...register('email')}
          className="rounded-md border border-neutral-300 px-3 py-2 text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-neutral-500"
        />
        {errors.email !== undefined ? (
          <p
            id="resend-email-error"
            aria-live="polite"
            className="text-sm text-red-700"
          >
            {errors.email.message}
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
        // NFR-03/NFR-04 (FE-05 Spinner pattern): announced, not just visible.
        <span role="status" aria-live="polite" className="sr-only">
          Sending a new link…
        </span>
      ) : null}

      <button
        type="submit"
        disabled={isSubmitting}
        aria-busy={isSubmitting}
        className="self-start rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
      >
        {isSubmitting ? 'Sending link…' : 'Send new link'}
      </button>
    </form>
  )
}
