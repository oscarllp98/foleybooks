import { useQuery } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { getBook } from '../../api/catalog'
import { AvailabilityBadge } from '../../components/BookCard'
import { EmptyState } from '../../components/EmptyState'
import { ErrorState } from '../../components/ErrorState'
import { Spinner } from '../../components/Spinner'
import { formatEur } from '../../lib/money'
import type { BookResponse } from '../../types/catalog'

// FE-13 (FR-07): the book detail page at /books/:id. Every field the FR names
// — title, ISBN, author, price (EUR), cover image, category, availability —
// comes straight from GET /books/{id}'s BookResponse (CA-09): the availability
// badge is the server's derived label (D-09), re-rendered, never re-derived
// here, and the price is only formatted (D-08 — no frontend money math).
//
// Read state follows the same NFR-03 contract as the list: spinner while
// pending, error state with the ProblemDetail traceId when the call fails —
// with one deliberate split. CA-09 answers a well-formed-but-unknown id with
// 404 book-not-found (unlike CA-11's batch omission, LC-14): a singular read
// has no "subset" to return, and the spec's 404 is exactly "missing" (§6), so
// the page shows a not-found state with a way back to browsing. The 400 half
// of the same contract — a malformed hand-typed id (C23) — is a request
// failure, not a missing book, and stays in the generic error state.
//
// The cover keeps FE-05's D-11 pattern: a failed Open-Library hotlink swaps
// to a styled placeholder that keeps the title as accessible text (LC-29).

/** The ProblemDetail properties this view can act on (D-15). */
interface BookProblem {
  status?: number
  traceId?: string
}

function isBookNotFound(error: unknown): boolean {
  return isAxiosError<BookProblem>(error) && error.response?.status === 404
}

function problemTraceId(error: unknown): string | undefined {
  if (!isAxiosError<BookProblem>(error)) return undefined
  return error.response?.data?.traceId
}

/**
 * FE-13 (FR-07): reachable from list and search results via the UUID the
 * card hands over (FE-11 navigates here); the route mounts it in AppRoutes.
 */
export function BookDetail() {
  const { id = '' } = useParams<{ id: string }>()
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['book', id],
    queryFn: () => getBook(id),
  })

  if (isPending) {
    return <Spinner label="Loading the book" />
  }

  if (isError) {
    return isBookNotFound(error) ? (
      <EmptyState
        title="Book not found"
        message="This book is not in the catalog. It may have been removed — the full selection is one click away."
      >
        <Link
          to="/"
          className="rounded-md bg-neutral-800 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-800"
        >
          Browse all books
        </Link>
      </EmptyState>
    ) : (
      <ErrorState
        title="We could not load the book"
        message="The catalog service did not answer. Please try again."
        traceId={problemTraceId(error)}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  }

  if (data === undefined) {
    return null
  }

  return <BookDetailView book={data} />
}

function BookDetailView({ book }: { book: BookResponse }) {
  return (
    <section
      aria-labelledby="book-detail-heading"
      className="flex flex-col gap-6"
    >
      <Link
        to="/"
        className="self-start text-sm font-medium text-neutral-600 hover:text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-800"
      >
        ← Back to browse
      </Link>
      <div className="grid gap-8 sm:grid-cols-[minmax(0,16rem)_minmax(0,1fr)]">
        <BookCover book={book} />
        <div className="flex flex-col gap-2">
          <h1
            id="book-detail-heading"
            className="text-2xl font-bold tracking-tight text-neutral-900"
          >
            {book.title}
          </h1>
          <p className="text-neutral-600">{book.author}</p>
          <p className="text-lg font-semibold text-neutral-900">
            {formatEur(book.price)}
          </p>
          <dl className="mt-2 grid gap-x-4 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
            <dt className="font-medium text-neutral-500">ISBN</dt>
            <dd className="text-neutral-900">{book.isbn}</dd>
            <dt className="font-medium text-neutral-500">Category</dt>
            <dd className="text-neutral-900">{book.category.name}</dd>
            <dt className="font-medium text-neutral-500">Availability</dt>
            <dd>
              <AvailabilityBadge availability={book.availability} />
            </dd>
          </dl>
        </div>
      </div>
    </section>
  )
}

/** FR-07 cover image with the LC-29/D-11 failure placeholder (FE-05 pattern). */
function BookCover({ book }: { book: BookResponse }) {
  const [coverFailed, setCoverFailed] = useState(false)

  return (
    <figure className="aspect-2/3 w-full max-w-64 self-start overflow-hidden rounded-lg border border-neutral-200 bg-white shadow-sm">
      {coverFailed ? (
        <div
          role="img"
          aria-label={`Cover image unavailable for ${book.title}`}
          className="flex h-full w-full items-center justify-center bg-neutral-100 p-4"
        >
          <figcaption className="text-center text-base font-medium text-neutral-500">
            {book.title}
          </figcaption>
        </div>
      ) : (
        <img
          src={book.coverUrl}
          alt={`Cover of ${book.title}`}
          onError={() => setCoverFailed(true)}
          className="h-full w-full object-cover"
        />
      )}
    </figure>
  )
}
