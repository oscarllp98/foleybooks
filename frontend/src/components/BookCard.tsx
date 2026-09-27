import { useState } from 'react'
import type { Availability, BookResponse } from '../types/catalog'
import { formatEur } from '../lib/money'

interface BookCardProps {
  book: BookResponse
  /** FR-07: reachability from list and search results — FE-11 wires the route. */
  onOpen: (book: BookResponse) => void
}

const AVAILABILITY_LABELS: Record<Availability, string> = {
  OUT_OF_STOCK: 'Out of stock',
  LOW_STOCK: 'Low stock',
  IN_STOCK: 'In stock',
}

const AVAILABILITY_STYLES: Record<Availability, string> = {
  OUT_OF_STOCK: 'bg-red-100 text-red-800',
  LOW_STOCK: 'bg-amber-100 text-amber-800',
  IN_STOCK: 'bg-green-100 text-green-800',
}

/** FE-05: availability badge derived server-side by AvailabilityPolicy (D-09). */
export function AvailabilityBadge({
  availability,
}: {
  availability: Availability
}) {
  return (
    <span
      className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${AVAILABILITY_STYLES[availability]}`}
    >
      {AVAILABILITY_LABELS[availability]}
    </span>
  )
}

/**
 * FE-05 (FR-06, LC-29, NFR-04): list card with cover, title, author, EUR
 * price and availability. A failed Open-Library cover hotlink (D-11) swaps
 * the image for a styled placeholder that keeps the title as accessible
 * text — browsing is otherwise unaffected (LC-29).
 */
export function BookCard({ book, onOpen }: BookCardProps) {
  const [coverFailed, setCoverFailed] = useState(false)

  return (
    <article
      aria-labelledby={`book-card-title-${book.id}`}
      className="flex flex-col overflow-hidden rounded-lg border border-neutral-200 bg-white shadow-sm transition-shadow hover:shadow-md"
    >
      <figure className="aspect-2/3 w-full">
        {coverFailed ? (
          <div
            role="img"
            aria-label={`Cover image unavailable for ${book.title}`}
            className="flex h-full w-full items-center justify-center bg-neutral-100 p-3"
          >
            <figcaption className="text-center text-sm font-medium text-neutral-500">
              {book.title}
            </figcaption>
          </div>
        ) : (
          <img
            src={book.coverUrl}
            alt={`Cover of ${book.title}`}
            loading="lazy"
            onError={() => setCoverFailed(true)}
            className="h-full w-full object-cover"
          />
        )}
      </figure>
      <div className="flex flex-1 flex-col gap-1 p-4">
        <h3
          id={`book-card-title-${book.id}`}
          className="font-semibold text-neutral-900"
        >
          {book.title}
        </h3>
        <p className="text-sm text-neutral-600">{book.author}</p>
        <p className="text-sm font-medium text-neutral-900">
          {formatEur(book.price)}
        </p>
        <div className="mt-1">
          <AvailabilityBadge availability={book.availability} />
        </div>
        <button
          type="button"
          onClick={() => onOpen(book)}
          className="mt-3 rounded-md bg-neutral-800 px-3 py-2 text-sm font-medium text-white hover:bg-neutral-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-800"
        >
          View details
        </button>
      </div>
    </article>
  )
}
