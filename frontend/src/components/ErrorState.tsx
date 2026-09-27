interface ErrorStateProps {
  title?: string
  /** What the user can say went wrong — generic, no internals (NFR-06). */
  message?: string
  /** NFR-06: the ProblemDetail traceId, shown so support can trace the failure. */
  traceId?: string
  onRetry?: () => void
  retryLabel?: string
}

/**
 * FE-05 (NFR-03, NFR-06): the shared failure state. role="alert" announces
 * it; the retry action keeps recovery keyboard-accessible (NFR-04).
 */
export function ErrorState({
  title = 'Something went wrong',
  message = 'We could not complete this request. Please try again.',
  traceId,
  onRetry,
  retryLabel = 'Try again',
}: ErrorStateProps) {
  return (
    <section
      role="alert"
      aria-label={title}
      className="flex flex-col items-center gap-3 rounded-lg border border-red-200 bg-red-50 px-6 py-10 text-center"
    >
      <h2 className="text-lg font-semibold text-red-800">{title}</h2>
      <p className="text-sm text-red-700">{message}</p>
      {traceId ? (
        <p className="text-xs text-red-600">Reference: {traceId}</p>
      ) : null}
      {onRetry ? (
        <button
          type="button"
          onClick={onRetry}
          className="mt-1 rounded-md bg-red-700 px-4 py-2 text-sm font-medium text-white hover:bg-red-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-700"
        >
          {retryLabel}
        </button>
      ) : null}
    </section>
  )
}
