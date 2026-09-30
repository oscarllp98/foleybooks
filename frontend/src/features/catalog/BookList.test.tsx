import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { BookList } from './BookList'
import { http } from '../../lib/http'
import { formatEur } from '../../lib/money'

// FE-11 + FE-12 tests (FR-06, FR-08, FR-09, NFR-03, LC-10, LC-31): the browse
// page driven over the real shared axios instance through a stubbed adapter —
// the same seam AppRoutes.test.tsx uses — with a fresh no-retry QueryClient
// per render so the pending, settled and failed query states are the
// component's own, not the client's retries.
//
// FE-12 adds a second resource to the page (GET /categories for the filter),
// so a responder now receives the whole request, not just a call index:
// booksResponder() answers categories from a fixture and numbers only the
// /books calls, which keeps the index-based expectations of the FE-11 tests
// meaningful and the request-shape assertions exact.

interface SentRequest {
  url: string
  params: Record<string, unknown>
}

type Response = [number, unknown] | Promise<[number, unknown]>
type Responder = (request: SentRequest) => Response

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

const CATEGORIES_PAGE = {
  content: [
    { id: 'cat-2', name: 'Fiction' },
    { id: 'cat-1', name: 'Technology' },
  ],
  page: { totalElements: 2, totalPages: 1, number: 0, size: 100 },
} as const

const EMPTY_CATEGORIES_PAGE = {
  content: [],
  page: { totalElements: 0, totalPages: 0, number: 0, size: 100 },
} as const

/** Answers /categories from the fixture and delegates everything else, so a
 *  test can count only the book requests its expectations are about. */
function booksResponder(
  handler: (bookRequestIndex: number) => Response,
): Responder {
  let bookRequests = 0
  return (request) =>
    request.url === '/categories'
      ? [200, CATEGORIES_PAGE]
      : handler(bookRequests++)
}

/** Renders the live route path, so FR-07's wiring is pinned through visible
 *  location state rather than component internals (constitution #11). */
function RouteProbe() {
  const { pathname } = useLocation()
  return <p>route: {pathname}</p>
}

function renderBookList(
  responder: Responder,
  initialEntry = '/',
): SentRequest[] {
  const books: SentRequest[] = []
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    const request: SentRequest = {
      url: config.url ?? '',
      params: (config.params ?? {}) as Record<string, unknown>,
    }
    if (request.url === '/books') {
      books.push(request)
    }
    const [status, data] = await responder(request)
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
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <BookList />
        <RouteProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return books
}

describe('BookList', () => {
  it('bookList_whenFirstPageArrives_rendersCardsSortDefaultAndResultSummary', async () => {
    // FR-06: the public list shows cover, title, author and EUR price per
    // card (FR-14-style envelope from api/catalog), the sort control starts at
    // the server default (title asc), and the result summary comes from the
    // page envelope. Only the whitelisted sort grammar is selectable (CA-07).
    const requests = renderBookList(booksResponder(() => [200, PAGE_ONE]))

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
      booksResponder(
        () =>
          new Promise<[number, unknown]>((resolve) => {
            settle = resolve
          }),
      ),
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
    const requests = renderBookList(
      booksResponder((index) =>
        index === 0
          ? [200, PAGE_ONE]
          : new Promise<[number, unknown]>((resolve) => {
              settle = resolve
            }),
      ),
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
    const requests = renderBookList(
      booksResponder((index) =>
        index === 0
          ? [200, PAGE_ONE]
          : index === 1
            ? [200, PAGE_TWO]
            : [200, PAGE_ONE],
      ),
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
    renderBookList(booksResponder(() => [200, EMPTY_PAGE]))

    expect(
      await screen.findByRole('region', { name: 'No books yet' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('pagination_whenResultFitsOnePage_rendersNoPaginationControls', async () => {
    renderBookList(booksResponder(() => [200, SINGLE_PAGE]))

    await screen.findByText('Clean Code')
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).toBeNull()
  })

  it('bookList_whenServerFails_showsErrorStateWithProblemTraceId', async () => {
    // NFR-03/NFR-06: the failure is a role="alert" state, generic copy plus
    // the ProblemDetail traceId — the gateway's 500 body, not a stack trace.
    renderBookList(
      booksResponder(() => [
        500,
        {
          type: 'urn:foley-books:problem:error',
          title: 'Internal server error',
          status: 500,
          detail: 'Unexpected failure.',
          traceId: 'f6a7b8c9',
        },
      ]),
    )

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('We could not load the books')
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')
    expect(screen.queryByText('Clean Code')).toBeNull()
  })

  it('errorState_whenRetryClicked_refetchesAndRendersTheBooks', async () => {
    // NFR-03/NFR-04: recovery is a visible action, keyboard-reachable, and it
    // replays the same request through the query layer (refetch, not remount).
    const user = userEvent.setup()
    const requests = renderBookList(
      booksResponder((index) =>
        index === 0
          ? [500, { status: 500, traceId: 'a1b2c3d4' }]
          : [200, PAGE_ONE],
      ),
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
    renderBookList(booksResponder(() => [200, PAGE_ONE]))
    await screen.findByText('Clean Code')

    await user.click(screen.getAllByRole('button', { name: 'View details' })[0])

    expect(
      await screen.findByText(`route: /books/${CLEAN_CODE.id}`),
    ).toBeInTheDocument()
  })

  it('categories_whenFilterLoads_requestsOneMaxSizePageInServerOrder', async () => {
    // FR-09: the filter's choices come from GET /categories in one read —
    // the server's clamped max page size (D-07) covers the whole seeded list,
    // in its name-ascending order (CA-10). The page never sorts options
    // itself, so client and server cannot drift.
    const categoryRequests: SentRequest[] = []
    renderBookList((request) => {
      if (request.url === '/categories') {
        categoryRequests.push(request)
        return [200, CATEGORIES_PAGE]
      }
      return [200, PAGE_ONE]
    })

    await screen.findByText('Clean Code')
    await screen.findByRole('option', { name: 'Fiction' })
    expect(categoryRequests).toHaveLength(1)
    expect(categoryRequests[0]?.params).toEqual({ page: 0, size: 100 })
    const optionValues = Array.from(
      (screen.getByLabelText('Category') as HTMLSelectElement).options,
    ).map((option) => option.value)
    expect(optionValues).toEqual(['', 'cat-2', 'cat-1'])
  })

  it('search_whenSubmittedAfterBrowsing_sendsTrimmedTermAndRestartsAtFirstPage', async () => {
    // FR-08: a submitted term goes out as the server's `search` parameter
    // (trimmed at the UI, though CA-08 trims too), and narrowing the result
    // set restarts browsing at page 0 instead of carrying an index from the
    // unfiltered list. Typing alone sends nothing — only submit does.
    const user = userEvent.setup()
    const requests = renderBookList(
      booksResponder((index) =>
        index === 0
          ? [200, PAGE_ONE]
          : index === 1
            ? [200, PAGE_TWO]
            : [200, PAGE_ONE],
      ),
    )
    await screen.findByText('Clean Code')
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    await screen.findByText('Neuromancer')

    await user.type(screen.getByLabelText('Search books'), '  gibson ')
    expect(requests).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Search' }))

    await screen.findByText('Clean Code')
    expect(requests[2]?.params).toEqual({
      page: 0,
      sort: 'title,asc',
      search: 'gibson',
    })
  })

  it('search_whenSubmittedBlank_clearsTheFilterAndRequestsTheWholeCatalog', async () => {
    // FR-08: submitting an empty box removes the search — the request omits
    // the parameter entirely rather than sending ?search=, which is the
    // server's own "no filter" spelling (CA-08).
    const user = userEvent.setup()
    const requests = renderBookList(
      booksResponder(() => [200, PAGE_ONE]),
      '/?search=gibson',
    )

    expect(
      (await screen.findByLabelText('Search books')) as HTMLInputElement,
    ).toHaveValue('gibson')
    expect(requests[0].params).toEqual({
      page: 0,
      sort: 'title,asc',
      search: 'gibson',
    })

    await user.clear(screen.getByLabelText('Search books'))
    await user.click(screen.getByRole('button', { name: 'Search' }))

    await waitFor(() =>
      expect(requests[1]?.params).toEqual({ page: 0, sort: 'title,asc' }),
    )
  })

  it('search_whenTypedPastTheServerCap_stopsAtTheMirroredMaxLength', async () => {
    // AGENTS §2's mirror rule: BookController's @Size(max = 300) on `search`
    // (CA-08, LC-28's long-query-string clause) lives on the input as
    // maxLength, so a term longer than the server would ever evaluate simply
    // cannot be typed — the browse UI can never walk a user into the
    // validation-400 error page through the search box.
    const user = userEvent.setup()
    renderBookList(booksResponder(() => [200, PAGE_ONE]))

    const input = (await screen.findByLabelText(
      'Search books',
    )) as HTMLInputElement
    expect(input.maxLength).toBe(300)

    await user.type(input, 'x'.repeat(301))
    expect(input).toHaveValue('x'.repeat(300))
  })

  it('searchAndCategory_whenCombined_composeIntoOneQueryAndSortKeepsBoth', async () => {
    // FR-08 + FR-09 (D-06): the two filters compose into a single request —
    // changing one never drops the other — and a sort change keeps both while
    // restarting at page 0.
    const user = userEvent.setup()
    const requests = renderBookList(
      booksResponder(() => [200, PAGE_ONE]),
      '/?search=gibson&page=1',
    )
    await screen.findByText('Clean Code')
    expect(requests[0].params).toEqual({
      page: 1,
      sort: 'title,asc',
      search: 'gibson',
    })

    await user.selectOptions(screen.getByLabelText('Category'), 'Fiction')
    await waitFor(() =>
      expect(requests[1]?.params).toEqual({
        page: 0,
        sort: 'title,asc',
        search: 'gibson',
        categoryId: 'cat-2',
      }),
    )

    await user.selectOptions(
      screen.getByLabelText('Sort by'),
      'Price: low to high',
    )
    await waitFor(() =>
      expect(requests[2]?.params).toEqual({
        page: 0,
        sort: 'price,asc',
        search: 'gibson',
        categoryId: 'cat-2',
      }),
    )
  })

  it('categoryFilter_whenAllCategoriesSelected_removesCategoryIdFromQuery', async () => {
    // FR-09: "All categories" is the filter's off switch — the parameter is
    // dropped, not sent blank, exactly like clearing the search box.
    const user = userEvent.setup()
    const requests = renderBookList(
      booksResponder(() => [200, PAGE_ONE]),
      '/?categoryId=cat-1',
    )
    await screen.findByText('Clean Code')
    expect((screen.getByLabelText('Category') as HTMLSelectElement).value).toBe(
      'cat-1',
    )

    await user.selectOptions(
      screen.getByLabelText('Category'),
      'All categories',
    )

    await waitFor(() =>
      expect(requests[1]?.params).toEqual({ page: 0, sort: 'title,asc' }),
    )
  })

  it('bookList_whenUrlCarriesEveryParam_restoresControlsAndSendsTheSameQuery', async () => {
    // FR-08/FR-09 shareability: a saved link restores search, category, page
    // and sort in both the visible controls and the first request — the URL
    // is the single source of truth for browse state.
    const requests = renderBookList(
      booksResponder(() => [200, PAGE_ONE]),
      '/?search=gibson&categoryId=cat-2&page=1&sort=price,desc',
    )

    await screen.findByText('Clean Code')
    expect(
      screen.getByLabelText('Search books') as HTMLInputElement,
    ).toHaveValue('gibson')
    expect((screen.getByLabelText('Category') as HTMLSelectElement).value).toBe(
      'cat-2',
    )
    expect((screen.getByLabelText('Sort by') as HTMLSelectElement).value).toBe(
      'price,desc',
    )
    expect(requests[0].params).toEqual({
      page: 1,
      sort: 'price,desc',
      search: 'gibson',
      categoryId: 'cat-2',
    })
  })

  it('bookList_whenSearchMatchesNothing_showsEmptyResultStateNotError', async () => {
    // LC-10: a LIKE-metacharacter term travels to the server as literal text
    // and its no-match answer is the empty state naming the term — the same
    // 200 page every other search gets, never an alert.
    const requests = renderBookList(
      booksResponder(() => [200, EMPTY_PAGE]),
      '/?search=dune%25',
    )

    const empty = await screen.findByRole('region', {
      name: 'No books match your search',
    })
    expect(empty).toHaveTextContent('dune%')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(requests[0].params).toEqual({
      page: 0,
      sort: 'title,asc',
      search: 'dune%',
    })
  })

  it('bookList_whenCategoryHasNoBooks_showsEmptyCategoryStateAndKeepsSelection', async () => {
    // LC-31: a category that answers with zero books — here one the fetched
    // list does not even carry — is a nothing-to-show state, and the select
    // still reflects the URL-driven choice that produced it.
    const requests = renderBookList(
      booksResponder(() => [200, EMPTY_PAGE]),
      '/?categoryId=cat-9',
    )

    expect(
      await screen.findByRole('region', { name: 'No books in this category' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect((screen.getByLabelText('Category') as HTMLSelectElement).value).toBe(
      'cat-9',
    )
    expect(requests[0].params).toEqual({
      page: 0,
      sort: 'title,asc',
      categoryId: 'cat-9',
    })
  })

  it('categoryFilter_whenCategoryListIsEmpty_degradesToAllCategoriesWithoutError', async () => {
    // FR-09/LC-31: an empty category list is nothing-to-show for the filter
    // too — just "All categories", no alert, and browsing the books is
    // completely unaffected.
    renderBookList((request) =>
      request.url === '/categories'
        ? [200, EMPTY_CATEGORIES_PAGE]
        : [200, PAGE_ONE],
    )

    await screen.findByText('Clean Code')
    const select = screen.getByLabelText('Category') as HTMLSelectElement
    expect(select).toHaveValue('')
    expect(select.options).toHaveLength(1)
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
