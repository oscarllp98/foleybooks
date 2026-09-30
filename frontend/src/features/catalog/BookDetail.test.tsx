import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BookDetail } from './BookDetail'
import { AuthProvider } from '../auth/AuthProvider'
import { http, setSessionExpiredHandler } from '../../lib/http'
import { clearTokens, setTokens } from '../../lib/tokens'
import { formatEur } from '../../lib/money'

// FE-13 tests (FR-07): the detail page over the real shared axios instance
// through the same adapter stub as BookList.test — rendered inside a route
// pattern so useParams sees the UUID the way the mounted table delivers it,
// with a fresh no-retry QueryClient per render. FE-14 mounts the add-to-cart
// control on this page, so the harness now includes the real AuthProvider
// (main.tsx's shape): the control branches on the ONE session store, and
// these tests only pin the two integration seams — signed-in readers get
// the control, anonymous visitors get the login prompt.

interface SentRequest {
  url: string
}

type Response = [number, unknown] | Promise<[number, unknown]>
type Responder = (request: SentRequest) => Response

const BOOK_ID = '00000000-0000-0000-0000-00000000cb06'

const USER = {
  id: 'u-1',
  email: 'reader@example.com',
  role: 'CUSTOMER',
} as const

const CLEAN_CODE = {
  id: BOOK_ID,
  title: 'Clean Code',
  author: 'Robert C. Martin',
  isbn: '9780132350884',
  price: 31.99,
  coverUrl: 'https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg',
  availability: 'IN_STOCK',
  stockQuantity: 12,
  category: { id: 'cat-1', name: 'Technology' },
} as const

const OUT_OF_STOCK_BOOK = {
  ...CLEAN_CODE,
  availability: 'OUT_OF_STOCK',
  stockQuantity: 0,
} as const

const NOT_FOUND_PROBLEM = {
  type: 'urn:foley-books:problem:book-not-found',
  title: 'Book not found',
  status: 404,
  detail: 'No book with id 9f9a0b1c-0000-0000-0000-000000000000.',
  traceId: 'a1b2c3d4',
} as const

function renderBookDetail(
  responder: Responder,
  path = `/books/${BOOK_ID}`,
): SentRequest[] {
  const requests: SentRequest[] = []
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    const request: SentRequest = { url: config.url ?? '' }
    requests.push(request)
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
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/books/:id" element={<BookDetail />} />
            <Route path="/" element={<p>browse landing</p>} />
          </Routes>
          <RouteProbe />
        </MemoryRouter>
      </QueryClientProvider>
    </AuthProvider>,
  )
  return requests
}

/** Live route position, so navigation outcomes are pinned through visible
 *  location state rather than component internals (constitution #11). */
function RouteProbe() {
  const { pathname } = useLocation()
  return <p>route: {pathname}</p>
}

function signIn(): void {
  setTokens({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    user: USER,
  })
}

beforeEach(() => {
  // FE-14 seam: the detail page now hosts an auth-branching control, so the
  // suite starts every test from the normalized anonymous store, like the
  // Header/LoginForm suites.
  setTokens({ accessToken: 'reset', refreshToken: 'reset' })
  clearTokens()
  setSessionExpiredHandler(null)
  localStorage.clear()
})

describe('BookDetail', () => {
  it('bookDetail_whenBookArrives_rendersEveryFr07Field', async () => {
    // FR-07: title, ISBN, author, EUR price, cover image, category and
    // availability, fetched by the public UUID the card handed to the route.
    const requests = renderBookDetail(() => [200, CLEAN_CODE])

    await screen.findByText('9780132350884')
    expect(requests[0].url).toBe(`/books/${BOOK_ID}`)

    expect(
      screen.getByRole('heading', { level: 1, name: 'Clean Code' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Robert C. Martin')).toBeInTheDocument()
    expect(screen.getByText(formatEur(31.99))).toBeInTheDocument()
    expect(
      screen.getByRole('img', { name: 'Cover of Clean Code' }),
    ).toBeInTheDocument()

    // The metadata reads as a definition list: every FR-07 field labelled.
    expect(screen.getByText('ISBN')).toBeInTheDocument()
    expect(screen.getByText('Category')).toBeInTheDocument()
    expect(screen.getByText('Availability')).toBeInTheDocument()
    expect(screen.getByText('Technology')).toBeInTheDocument()
    expect(screen.getByText('In stock')).toBeInTheDocument()
  })

  it('bookDetail_whenRequestIsInFlight_showsLoadingStateThenTheBook', async () => {
    // NFR-03: the fetch is announced and visibly pending before the detail
    // replaces it.
    let settle: ((pair: [number, unknown]) => void) | undefined
    renderBookDetail(
      () =>
        new Promise<[number, unknown]>((resolve) => {
          settle = resolve
        }),
    )

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Loading the book',
    )
    expect(screen.queryByText('Clean Code')).toBeNull()

    settle?.([200, CLEAN_CODE])
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Clean Code' }),
    ).toBeInTheDocument()
    // The spinner — and only the spinner — is gone; the add-to-cart prompt
    // legitimately carries its own status role (FE-14).
    expect(screen.queryByText('Loading the book')).toBeNull()
  })

  it('availability_whenOutOfStock_rendersOutOfStockBadge', async () => {
    // FR-07/D-09: the badge is the server's derived label rendered as-is —
    // the thresholds live in AvailabilityPolicy, not here.
    renderBookDetail(() => [200, OUT_OF_STOCK_BOOK])

    expect(await screen.findByText('Out of stock')).toBeInTheDocument()
  })

  it('bookDetail_whenIdNamesNoBook_showsNotFoundStateNotError', async () => {
    // CA-09's 404 half (plan §2): the singular read says "missing", and this
    // view answers with the nothing-to-show state plus a way back — never a
    // generic alert, and never an empty detail page.
    renderBookDetail(() => [404, NOT_FOUND_PROBLEM])

    const empty = await screen.findByRole('region', { name: 'Book not found' })
    expect(within(empty).getByText(/not in the catalog/)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()

    await userEvent
      .setup()
      .click(within(empty).getByRole('link', { name: 'Browse all books' }))
    expect(await screen.findByText('route: /')).toBeInTheDocument()
  })

  it('bookDetail_whenServerFails_showsErrorStateWithProblemTraceId', async () => {
    // NFR-03/NFR-06: a non-404 failure is the shared alert state with the
    // ProblemDetail traceId (D-15) and a retry.
    renderBookDetail(() => [
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
    expect(alert).toHaveTextContent('We could not load the book')
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')
    expect(screen.queryByRole('region', { name: 'Book not found' })).toBeNull()
  })

  it('errorState_whenRetryClicked_refetchesTheSameBook', async () => {
    // NFR-04: recovery replays the fetch through the query layer.
    const user = userEvent.setup()
    let attempts = 0
    const requests = renderBookDetail(() =>
      ++attempts === 1
        ? [500, { status: 500, traceId: 'a1b2c3d4' }]
        : [200, CLEAN_CODE],
    )

    const alert = await screen.findByRole('alert')
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Clean Code' }),
    ).toBeInTheDocument()
    expect(requests).toHaveLength(2)
    expect(requests[1].url).toBe(`/books/${BOOK_ID}`)
  })

  it('bookDetail_whenBackLinkClicked_returnsToBrowse', async () => {
    // FR-07 "reachable from list and search results" — reachability is two
    // doors wide: the card navigates in (BookList.test pins that), this
    // link navigates out.
    const user = userEvent.setup()
    renderBookDetail(() => [200, CLEAN_CODE])
    await screen.findByText('Clean Code')

    await user.click(screen.getByRole('link', { name: '← Back to browse' }))

    expect(await screen.findByText('route: /')).toBeInTheDocument()
  })

  it('cover_whenImageFailsToLoad_showsPlaceholderKeepingTitle', async () => {
    // LC-29/D-11 on the detail page: a dead Open-Library hotlink swaps to the
    // placeholder; the rest of the fields keep rendering.
    renderBookDetail(() => [200, CLEAN_CODE])
    const img = (await screen.findByRole('img', {
      name: 'Cover of Clean Code',
    })) as HTMLImageElement
    img.dispatchEvent(new Event('error'))

    await vi.waitFor(() => {
      expect(
        screen.queryByRole('img', { name: 'Cover of Clean Code' }),
      ).toBeNull()
    })
    const placeholder = screen.getByRole('img', {
      name: 'Cover image unavailable for Clean Code',
    })
    expect(placeholder).toHaveTextContent('Clean Code')
    expect(screen.getByText('9780132350884')).toBeInTheDocument()
  })

  it('bookDetail_whenSignedIn_mountsTheAddToCartControl', async () => {
    // FE-14 seam (plan §6.5 "add-to-cart"): the detail page hosts the FR-10
    // control with quantity selectable at add time, default 1, ceiling the
    // book's stock. The control's own behavior is pinned by its suite.
    signIn()
    renderBookDetail(() => [200, CLEAN_CODE])
    await screen.findByText('Clean Code')

    expect(screen.getByRole('button', { name: 'Add to cart' })).toBeEnabled()
    expect(screen.getByLabelText('Quantity')).toHaveValue(1)
    expect(screen.getByLabelText('Quantity')).toHaveAttribute('max', '12')
  })

  it('bookDetail_whenAnonymous_showsLoginPromptInsteadOfAddControl', async () => {
    // FE-14 seam (FR-10, LC-27): the page visitors reach without a session
    // is exactly where the no-guest-cart prompt appears — offer to sign in,
    // mount no add control.
    renderBookDetail(() => [200, CLEAN_CODE])
    await screen.findByText('Clean Code')

    expect(
      screen.getByRole('link', { name: 'Sign in to add to cart' }),
    ).toHaveAttribute('href', '/login')
    expect(screen.queryByRole('button', { name: 'Add to cart' })).toBeNull()
  })
})
