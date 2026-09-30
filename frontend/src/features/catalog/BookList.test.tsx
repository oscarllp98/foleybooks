import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { BookList } from './BookList'
import { http } from '../../lib/http'
import { formatEur } from '../../lib/money'

// FE-11 tests (FR-06, NFR-03): the browse page driven over the real shared
// axios instance through a stubbed adapter — the same seam AppRoutes.test.tsx
// uses — with a fresh no-retry QueryClient per render so the pending, settled
// and failed query states are the component's own, not the client's retries.

interface SentRequest {
  url: string
  params: Record<string, unknown>
}

type Response = [number, unknown] | Promise<[number, unknown]>
type Responder = (requestIndex: number) => Response

const CLEAN_CODE = {
  id: '00000000-0000-0000-0000-00000000cb06',
  title: 'Clean Code',
  author: 'Robert C. Martin',
  isbn: '9780132350884',
  price: 31.99,
  coverUrl: 'https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg',
  availability: 'IN_STOCK',
  stockQuantity: 12,
  category: { id: 'cat-1', name: 'Technology' },
} as const

const DUNE = {
  id: '00000000-0000-0000-0000-00000000dune1',
  title: 'Dune',
  author: 'Frank Herbert',
  isbn: '9780441013593',
  price: 9.5,
  coverUrl: 'https://covers.openlibrary.org/b/isbn/9780441013593-L.jpg',
  availability: 'LOW_STOCK',
  stockQuantity: 3,
  category: { id: 'cat-2', name: 'Fiction' },
} as const

const NEUROMANCER = {
  id: '00000000-0000-0000-0000-00000000neuro',
  title: 'Neuromancer',
  author: 'William Gibson',
  isbn: '9780441501244',
  price: 7.99,
  coverUrl: 'https://covers.openlibrary.org/b/isbn/9780441501244-L.jpg',
  availability: 'IN_STOCK',
  stockQuantity: 20,
  category: { id: 'cat-2', name: 'Fiction' },
} as const

const PAGE_ONE = {
  content: [CLEAN_CODE, DUNE],
  page: { totalElements: 24, totalPages: 2, number: 0, size: 20 },
} as const

const PAGE_TWO = {
  content: [NEUROMANCER],
  page: { totalElements: 24, totalPages: 2, number: 1, size: 20 },
} as const

const SINGLE_PAGE = {
  content: [CLEAN_CODE],
  page: { totalElements: 1, totalPages: 1, number: 0, size: 20 },
} as const

const EMPTY_PAGE = {
  content: [],
  page: { totalElements: 0, totalPages: 0, number: 0, size: 20 },
} as const

function installStub(responder: Responder): SentRequest[] {
  const sent: SentRequest[] = []
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    sent.push({
      url: config.url ?? '',
      params: (config.params ?? {}) as Record<string, unknown>,
    })
    const [status, data] = await responder(sent.length - 1)
    const response: AxiosResponse = {
      status,
      statusText: '',
      headers: {},
      config,
      data,
    }
    if (status >= 400) {
      throw new AxiosError(
        `Request failed with status code ${status}`,
        AxiosError.ERR_BAD_REQUEST,
        config,
        {},
        response,
      )
    }
    return response
  }
  return sent
}

/** Renders the live route path, so FR-07's wiring is pinned through visible
 *  location state rather than component internals (constitution #11). */
function RouteProbe() {
  const { pathname } = useLocation()
  return <p>route: {pathname}</p>
}

function renderBookList(responder: Responder): SentRequest[] {
  const requests = installStub(responder)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <BookList />
        <RouteProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return requests
}

describe('BookList', () => {
  it('bookList_whenFirstPageArrives_rendersCardsSortDefaultAndResultSummary', async () => {
    // FR-06: the public list shows cover, title, author and EUR price per
    // card (FR-14-style envelope from api/catalog), the sort control starts at
    // the server default (title asc), and the result summary comes from the
    // page envelope. Only the whitelisted sort grammar is selectable (CA-07).
    const requests = renderBookList(() => [200, PAGE_ONE])

    expect(
      screen.getByRole('heading', { name: 'Browse books' }),
    ).toBeInTheDocument()
    expect((screen.getByLabelText('Sort by') as HTMLSelectElement).value).toBe(
      'title,asc',
    )

    await screen.findByText('Clean Code')
    expect(requests[0].url).toBe('/books')
    expect(requests[0].params).toEqual({ page: 0, sort: 'title,asc' })

    expect(screen.getByText('Robert C. Martin')).toBeInTheDocument()
    expect(screen.getByText(formatEur(31.99))).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Dune' })).toBeInTheDocument()
    expect(screen.getByText('Low stock')).toBeInTheDocument()

    const nav = screen.getByRole('navigation', { name: 'Pagination' })
    expect(within(nav).getByText(/Page 1 of 2/)).toBeInTheDocument()
    expect(within(nav).getByText(/24 results/)).toBeInTheDocument()
  })

  it('bookList_whenRequestIsInFlight_showsLoadingStateThenTheBooks', async () => {
    // NFR-03: the fetch is announced and visibly pending before content
    // replaces it — the grid is never a silent blank page.
    let settle: ((pair: [number, unknown]) => void) | undefined
    renderBookList(
      () =>
        new Promise<[number, unknown]>((resolve) => {
          settle = resolve
        }),
    )

    expect(await screen.findByRole('status')).toHaveTextContent('Loading books')
    expect(screen.queryByText('Clean Code')).toBeNull()

    settle?.([200, PAGE_ONE])
    expect(await screen.findByText('Clean Code')).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('pagination_whenNextPageClicked_refetchesPageTwoKeepingTheOldGridVisible', async () => {
    // FR-06/LC-11 contract: page two is requested as ?page=1 (0-based, exactly
    // as the envelope reported it), the old page stays on screen dimmed behind
    // the spinner (keepPreviousData), and the new page replaces it on arrival.
    const user = userEvent.setup()
    let settle: ((pair: [number, unknown]) => void) | undefined
    const requests = renderBookList((index) =>
      index === 0
        ? [200, PAGE_ONE]
        : new Promise<[number, unknown]>((resolve) => {
            settle = resolve
          }),
    )
    await screen.findByText('Clean Code')

    await user.click(screen.getByRole('button', { name: 'Next page' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Loading the next books',
    )
    expect(requests[1].params).toEqual({ page: 1, sort: 'title,asc' })
    expect(screen.getByText('Clean Code')).toBeInTheDocument()

    settle?.([200, PAGE_TWO])
    expect(await screen.findByText('Neuromancer')).toBeInTheDocument()
    expect(screen.queryByText('Clean Code')).toBeNull()
    expect(
      within(screen.getByRole('navigation', { name: 'Pagination' })).getByText(
        'Page 2 of 2 — 24 results',
      ),
    ).toBeInTheDocument()
  })

  it('sort_whenChangedToPriceDesc_requestsWhitelistedSortAndRestartsAtFirstPage', async () => {
    // FR-06: sorting re-ranks the whole catalog, so browsing restarts at page
    // 0 with the whitelisted 'price,desc' — the request, not just the select,
    // proves the URL state is what drives the fetch.
    const user = userEvent.setup()
    const requests = renderBookList((index) =>
      index === 0
        ? [200, PAGE_ONE]
        : index === 1
          ? [200, PAGE_TWO]
          : [200, PAGE_ONE],
    )
    await screen.findByText('Clean Code')
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    await screen.findByText('Neuromancer')

    await user.selectOptions(
      screen.getByLabelText('Sort by'),
      'Price: high to low',
    )

    expect((screen.getByLabelText('Sort by') as HTMLSelectElement).value).toBe(
      'price,desc',
    )
    expect(await screen.findByText('Clean Code')).toBeInTheDocument()
    expect(requests[2].params).toEqual({ page: 0, sort: 'price,desc' })
  })

  it('bookList_whenCatalogEmpty_showsEmptyStateNotError', async () => {
    // FR-06 "each list state has an empty state": a settled page with nothing
    // in it is a message, never an alert.
    renderBookList(() => [200, EMPTY_PAGE])

    expect(
      await screen.findByRole('region', { name: 'No books yet' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('pagination_whenResultFitsOnePage_rendersNoPaginationControls', async () => {
    renderBookList(() => [200, SINGLE_PAGE])

    await screen.findByText('Clean Code')
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).toBeNull()
  })

  it('bookList_whenServerFails_showsErrorStateWithProblemTraceId', async () => {
    // NFR-03/NFR-06: the failure is a role="alert" state, generic copy plus
    // the ProblemDetail traceId — the gateway's 500 body, not a stack trace.
    renderBookList(() => [
      500,
      {
        type: 'urn:foley-books:problem:error',
        title: 'Internal server error',
        status: 500,
        detail: 'Unexpected failure.',
        traceId: 'f6a7b8c9',
      },
    ])

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('We could not load the books')
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')
    expect(screen.queryByText('Clean Code')).toBeNull()
  })

  it('errorState_whenRetryClicked_refetchesAndRendersTheBooks', async () => {
    // NFR-03/NFR-04: recovery is a visible action, keyboard-reachable, and it
    // replays the same request through the query layer (refetch, not remount).
    const user = userEvent.setup()
    const requests = renderBookList((index) =>
      index === 0
        ? [500, { status: 500, traceId: 'a1b2c3d4' }]
        : [200, PAGE_ONE],
    )

    const alert = await screen.findByRole('alert')
    expect(
      within(alert).getByRole('button', { name: 'Try again' }),
    ).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Clean Code')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(requests).toHaveLength(2)
    expect(requests[1].params).toEqual({ page: 0, sort: 'title,asc' })
  })

  it('bookList_whenViewDetailsClicked_navigatesToBookDetailRoute', async () => {
    // FR-07 reachability (constitution #5): the list hands the public UUID to
    // the /books/:id slot FE-13 mounts. BookCard.test pins the onOpen
    // callback; only this page can pin the navigation it is wired to.
    const user = userEvent.setup()
    renderBookList(() => [200, PAGE_ONE])
    await screen.findByText('Clean Code')

    await user.click(screen.getAllByRole('button', { name: 'View details' })[0])

    expect(
      await screen.findByText(`route: /books/${CLEAN_CODE.id}`),
    ).toBeInTheDocument()
  })
})
