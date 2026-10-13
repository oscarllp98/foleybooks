import type { ReactNode } from 'react'

interface EmptyStateProps {
  title: string
  message?: string
  /** Call to action (FR-11: "browse" prompt) — whatever slot the feature needs. */
  children?: ReactNode
}

/**
 * FE-05 (NFR-03, LC-31): nothing-to-show state, never an error. FR-06/FR-08
 * empty search results and FR-11's empty cart all render through this.
 */
export function EmptyState({ title, message, children }: EmptyStateProps) {
  return (
    <section
      aria-label={title}
      className="flex flex-col items-center gap-3 rounded-lg border border-neutral-200 bg-neutral-50 px-6 py-12 text-center"
    >
      <h2 className="text-lg font-semibold text-neutral-800">{title}</h2>
      {message ? <p className="text-sm text-neutral-600">{message}</p> : null}
      {children}
    </section>
  )
}
