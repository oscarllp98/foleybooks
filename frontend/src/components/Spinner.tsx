import type { ReactNode } from 'react'

interface SpinnerProps {
  /** Screen-reader label for the live region; defaults to "Loading". */
  label?: string
  children?: ReactNode
}

/**
 * FE-05 (NFR-03): the shared loading state. The sr-only label carries
 * role="status" + aria-live so assistive tech reads it when a fetch starts
 * and settles, and visually hidden children (e.g. the stale list) stay laid
 * out beneath it.
 */
export function Spinner({ label = 'Loading', children }: SpinnerProps) {
  return (
    <div className="relative">
      <div className="flex min-h-24 items-center justify-center">
        <span
          aria-hidden="true"
          className="inline-block size-8 animate-spin rounded-full border-2 border-neutral-300 border-t-neutral-700 motion-reduce:animate-none"
        />
        <span role="status" aria-live="polite" className="sr-only">
          {label}
        </span>
      </div>
      {children ? (
        <div aria-hidden="true" className="pointer-events-none opacity-50">
          {children}
        </div>
      ) : null}
    </div>
  )
}
