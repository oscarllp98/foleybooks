import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import {
  getBooks,
  getCategories,
  type BookSortParam,
} from '../../api/catalog'
import { BookCard } from '../../components/BookCard'
import { EmptyState } from '../../components/EmptyState'
import { ErrorState } from '../../components/ErrorState'
import { Pagination } from '../../components/Pagination'
import { Spinner } from '../../components/Spinner'
import type { CategoryResponse } from '../../types/catalog'

// FE-11 (FR-06, NFR-03) + FE-12 (FR-08, FR-09): the browse page — public
// paginated catalog list with the search box, the category filter, the sort
// control and the loading / empty / error states. Data flows through
// api/catalog over TanStack Query and nothing else (C10); BookList renders
// what the page envelope says and never filters, sorts or clamps locally.
//
// Browse state lives in the URL (?search=&categoryId=&page=&sort=): all four
// compose into the one GET /books query (D-06), and any of them makes a view
// shareable and reload/back-forward safe. Search, category and sort changes
// delete `page` — each one re-ranks or shrinks the result set, so a page index
// carried over from the previous query would open on an arbitrary middle of a
// list the user never asked for (FR-06, FR-08, FR-09).
//
// The client only ever emits whitelisted values for sort (the CA-07 grammar as
// select options) and a non-negative integer page. `search` and `categoryId`
// are forwarded verbatim once committed: narrowing to nothing — a term with
// LIKE metacharacters, an id naming no category — is the server's empty-page
// answer (LC-10, LC-31), not something to pre-empt here. Only a hand-edited
// non-UUID ?categoryId= can reach the malformed-parameter 400 (LC-28), and
// that ProblemDetail renders through the same error state as any other failure.
//
// The search box is a plain controlled input, deliberately not react-hook-form
// + zod: D-13 scopes that stack to request-DTO validation, and a browse filter
// submits no DTO — the server owns trim/blank/match semantics at its own
// boundary (C23), and constitution #1/#3 let the simpler solution win. The one
// rule worth mirroring is the length cap: CA-08's @Size(max = 300) on `search`
// (LC-28's long-query-string clause) becomes the input's maxLength, so the
// browse UI can never submit a term the server answers with a validation 400
// rendered as the generic error page.

const SORT_OPTIONS: readonly { value: BookSortParam; label: string }[] = [
  { value: 'title,asc', label: 'Title: A to Z' },
  { value: 'title,desc', label: 'Title: Z to A' },
  { value: 'price,asc', label: 'Price: low to high' },
  { value: 'price,desc', label: 'Price: high to low' },
]

// FR-06 default: title ascending. An absent or foreign ?sort= falls back here
// instead of being forwarded — the select and the request can never disagree.
const DEFAULT_SORT: BookSortParam = SORT_OPTIONS[0].value

// The category filter is one read, not a paginated surface: the server clamps
// size to this maximum anyway (D-07, LC-11) and FR-14 seeds three categories,
// so a single request fetches the whole list, in CA-10's name-ascending order.
const CATEGORY_FILTER_SIZE = 100

// Mirrors CA-08's @Size(max = 300) on `search` (BookController.MAX_SEARCH_LENGTH,
// the ADR-003 title column width): a longer term cannot match anything, so the
// input prevents it instead of letting the user reach the validation 400.
const MAX_SEARCH_LENGTH = 300

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
  const search = searchParams.get('search') ?? ''
  const categoryId = searchParams.get('categoryId') ?? ''

  // The input holds a draft so keystrokes never touch the catalog or the
  // history stack — only submitting commits the trimmed term to the URL. When
  // the committed search comes back different (back/forward, an edited URL),
  // the draft adopts it during render: React's "adjust state when a prop
  // changes" recipe, so box and request can never disagree without an effect.
  const [searchDraft, setSearchDraft] = useState(search)
  const [committedSearch, setCommittedSearch] = useState(search)
  if (search !== committedSearch) {
    setCommittedSearch(search)
    setSearchDraft(search)
  }

  // No `size`: FR-06 fixes the page at 20 and the server's default already is
  // 20 — mirroring a constant the server owns would only add a second value
  // to drift. keepPreviousData keeps the current page on screen (dimmed by
  // Spinner) while the next one loads, so filtering never blanks the grid.
  // Blank filters are omitted, never sent empty: an absent search/categoryId
  // is CA-08's "no filter", and ?search= would be a second way to say nothing.
  const { data, isPending, isFetching, isError, error, refetch } = useQuery({
    queryKey: ['books', { page, sort, search, categoryId }],
    queryFn: () =>
      getBooks({
        page,
        sort,
        ...(search === '' ? {} : { search }),
        ...(categoryId === '' ? {} : { categoryId }),
      }),
    placeholderData: keepPreviousData,
  })

  // FR-09: the filter's choices are the server's own category list. While it
  // loads, or if that read fails, the filter degrades to just "All categories"
  // — browsing books never depends on it, and a missing option is a degraded
  // affordance, not an error state (LC-31).
  const { data: categoryPage } = useQuery({
    queryKey: ['categories'],
    queryFn: () => getCategories({ page: 0, size: CATEGORY_FILTER_SIZE }),
  })
  const categories: readonly CategoryResponse[] = categoryPage?.content ?? []
  // A shared link can name a categoryId the fetched list no longer carries.
  // The URL drives the request either way (a well-formed unknown id is an
  // empty page, LC-31), so the unknown id rides along as its own option to
  // keep the select honest about what is actually being asked for.
  const categoryOptions =
    categoryId !== '' && !categories.some((entry) => entry.id === categoryId)
      ? [{ id: categoryId, name: categoryId }, ...categories]
      : categories

  const updateUrl = (mutate: (params: URLSearchParams) => void) => {
    setSearchParams((previous) => {
      const params = new URLSearchParams(previous)
      mutate(params)
      return params
    })
  }

  const goToPage = (next: number) => {
    updateUrl((params) => {
      params.set('page', String(next))
    })
  }

  const changeSort = (selected: string) => {
    const option = SORT_OPTIONS.find(
      (candidate) => candidate.value === selected,
    )
    if (option === undefined) return
    updateUrl((params) => {
      params.set('sort', option.value)
      // A new sort re-ranks the whole catalog — "page 3" of the old order
      // means nothing in the new one (FR-06), so browsing restarts at 0.
      params.delete('page')
    })
  }

  const submitSearch = () => {
    const term = searchDraft.trim()
    updateUrl((params) => {
      if (term === '') {
        params.delete('search')
      } else {
        params.set('search', term)
      }
      // The term narrows the result set — restart browsing at page 0 (FR-08).
      params.delete('page')
    })
  }

  const changeCategory = (selected: string) => {
    updateUrl((params) => {
      if (selected === '') {
        params.delete('categoryId')
      } else {
        params.set('categoryId', selected)
      }
      // Same restart rule as search and sort: a category is a different
      // result set, not a re-ranking of this one (FR-09).
      params.delete('page')
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

      <div className="flex flex-wrap items-center gap-4">
        <form
          role="search"
          onSubmit={(event) => {
            event.preventDefault()
            submitSearch()
          }}
          className="flex min-w-64 flex-1 items-center gap-2"
        >
          <label className="flex flex-1 items-center gap-2 text-sm text-neutral-700">
            Search books
            <input
              type="search"
              value={searchDraft}
              onChange={(event) => {
                setSearchDraft(event.target.value)
              }}
              maxLength={MAX_SEARCH_LENGTH}
              placeholder="Title or author"
              className="w-full min-w-0 rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-800"
            />
          </label>
          <button
            type="submit"
            className="rounded-md bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-800"
          >
            Search
          </button>
        </form>
        <label className="flex items-center gap-2 text-sm text-neutral-700">
          Category
          <select
            value={categoryId}
            onChange={(event) => {
              changeCategory(event.target.value)
            }}
            className="rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm text-neutral-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-800"
          >
            <option value="">All categories</option>
            {categoryOptions.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
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
        search !== '' ? (
          // FR-08, LC-10: a search with no matches — literal special
          // characters included — is a result state, never an error page.
          <EmptyState
            title="No books match your search"
            message={`Nothing was found for "${search}". Try fewer words, or clear the search box to browse everything.`}
          />
        ) : categoryId !== '' ? (
          // FR-09, LC-31: a category with zero books — including one a shared
          // link names that no longer exists — is nothing-to-show, not an error.
          <EmptyState
            title="No books in this category"
            message="This category has no books yet. Choose All categories to browse the whole catalog."
          />
        ) : (
          // FR-06 "each list state has an empty state": the catalog itself is
          // seeded (FR-14), so this is the nothing-to-show branch, never an error.
          <EmptyState
            title="No books yet"
            message="The catalog has no books to show right now. Please check back soon."
          />
        )
      ) : isFetching ? (
        <Spinner label="Loading the next books">{booksView}</Spinner>
      ) : (
        booksView
      )}
    </section>
  )
}
