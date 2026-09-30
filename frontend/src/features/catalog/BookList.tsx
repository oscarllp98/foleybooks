import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { useNavigate, useSearchParams } from 'react-router'
import { getBooks, type BookSortParam } from '../../api/catalog'
import { BookCard } from '../../components/BookCard'
import { EmptyState } from '../../components/EmptyState'
import { ErrorState } from '../../components/ErrorState'
import { Pagination } from '../../components/Pagination'
import { Spinner } from '../../components/Spinner'

// FE-11 (FR-06, NFR-03): the browse page — public paginated catalog list with
// the sort control and the loading / empty / error states. Data flows through
// api/catalog over TanStack Query and nothing else (C10); BookList renders
// what the page envelope says and never filters, sorts or clamps locally.
//
// Browse state lives in the URL (?page=&sort=): the list is public, so any
// state must be shareable and survive reload/back-forward exactly like the
// server's own contract. `page` is 0-based — the same convention the gateway
// exposes (AGENTS.md §6), so no conversion bug can shift the view by one.
// FE-12 grows this same param handling with `search` and `categoryId`.
//
// The client only ever emits whitelisted values: the CA-07 sort grammar
// (title/price ±asc/desc) as select options, and a non-negative integer page.
// The server owns clamping and malformed-input rejection (D-06/D-07, LC-11,
// LC-28) — if its 400 ProblemDetail ever comes back anyway, it renders through
// the same error state as any other failure.

const SORT_OPTIONS: readonly { value: BookSortParam; label: string }[] = [
  { value: 'title,asc', label: 'Title: A to Z' },
  { value: 'title,desc', label: 'Title: Z to A' },
  { value: 'price,asc', label: 'Price: low to high' },
  { value: 'price,desc', label: 'Price: high to low' },
]

// FR-06 default: title ascending. An absent or foreign ?sort= falls back here
// instead of being forwarded — the select and the request can never disagree.
const DEFAULT_SORT: BookSortParam = SORT_OPTIONS[0].value

function parsePage(raw: string | null): number {
  const parsed = Number(raw)
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0
}

function parseSort(raw: string | null): BookSortParam {
  return (
    SORT_OPTIONS.find((option) => option.value === raw)?.value ?? DEFAULT_SORT
  )
}

/** The ProblemDetail properties this view can act on (D-15). */
interface BooksProblem {
  traceId?: string
}

function problemTraceId(error: unknown): string | undefined {
  if (!isAxiosError<BooksProblem>(error)) return undefined
  return error.response?.data?.traceId
}

export function BookList() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const page = parsePage(searchParams.get('page'))
  const sort = parseSort(searchParams.get('sort'))

  // No `size`: FR-06 fixes the page at 20 and the server's default already is
  // 20 — mirroring a constant the server owns would only add a second value
  // to drift. keepPreviousData keeps the current page on screen (dimmed by
  // Spinner) while the next one loads, so pagination never blanks the grid.
  const { data, isPending, isFetching, isError, error, refetch } = useQuery({
    queryKey: ['books', { page, sort }],
    queryFn: () => getBooks({ page, sort }),
    placeholderData: keepPreviousData,
  })

  const goToPage = (next: number) => {
    setSearchParams((previous) => {
      const params = new URLSearchParams(previous)
      params.set('page', String(next))
      return params
    })
  }

  const changeSort = (selected: string) => {
    const option = SORT_OPTIONS.find(
      (candidate) => candidate.value === selected,
    )
    if (option === undefined) return
    setSearchParams((previous) => {
      const params = new URLSearchParams(previous)
      params.set('sort', option.value)
      // A new sort re-ranks the whole catalog — "page 3" of the old order
      // means nothing in the new one (FR-06), so browsing restarts at 0.
      params.delete('page')
      return params
    })
  }

  const booksView =
    data === undefined ? null : (
      <>
        <ul
          role="list"
          className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3"
        >
          {data.content.map((book) => (
            <li key={book.id}>
              <BookCard
                book={book}
                // FR-07 reachability: the /books/:id slot mounts in FE-13;
                // the list's job is only to hand the public UUID over.
                onOpen={(opened) => {
                  navigate(`/books/${opened.id}`)
                }}
              />
            </li>
          ))}
        </ul>
        <Pagination
          page={data.page.number}
          totalPages={data.page.totalPages}
          totalElements={data.page.totalElements}
          onPageChange={goToPage}
        />
      </>
    )

  return (
    <section
      aria-labelledby="book-list-heading"
      className="flex flex-col gap-6"
    >
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1
          id="book-list-heading"
          className="text-2xl font-bold tracking-tight text-neutral-900"
        >
          Browse books
        </h1>
        <label className="flex items-center gap-2 text-sm text-neutral-700">
          Sort by
          <select
            value={sort}
            onChange={(event) => {
              changeSort(event.target.value)
            }}
            className="rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-800"
          >
            {SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      {isPending ? (
        <Spinner label="Loading books" />
      ) : isError ? (
        <ErrorState
          title="We could not load the books"
          message="The catalog service did not answer. Please try again."
          traceId={problemTraceId(error)}
          onRetry={() => {
            void refetch()
          }}
        />
      ) : data === undefined ? null : data.content.length === 0 ? (
        // FR-06 "each list state has an empty state": the catalog itself is
        // seeded (FR-14), so this is the nothing-to-show branch, never an error.
        <EmptyState
          title="No books yet"
          message="The catalog has no books to show right now. Please check back soon."
        />
      ) : isFetching ? (
        <Spinner label="Loading the next books">{booksView}</Spinner>
      ) : (
        booksView
      )}
    </section>
  )
}
