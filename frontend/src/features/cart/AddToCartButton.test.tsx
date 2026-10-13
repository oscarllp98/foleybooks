import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { AddToCartButton } from './AddToCartButton'
import { AuthProvider } from '../auth/AuthProvider'
import { getCart } from '../../api/orders'
import { http, setSessionExpiredHandler } from '../../lib/http'
import { clearTokens, setTokens } from '../../lib/tokens'
import type { LoginRedirectState } from '../../types/routing'
import type { BookResponse } from '../../types/catalog'

// FE-14 tests (FR-10, LC-12, LC-27; plan §6.5 "AddToCartButton"): driven
// through the real AuthProvider (the auth branch reads the ONE session store,
// ADR-012) and the real TanStack Query + shared axios stack over a stubbed
// adapter, exactly as the LoginForm/Header suites do — the seam under test is
// user-visible behavior, so no internal of the control is mocked.

interface SentRequest {
  method: string
  url: string
  body?: unknown
}

interface OutgoingRequest {
  method: string
  url: string
}

type Responder = (request: OutgoingRequest) => [number, unknown]

// A real v4 UUID (plan §2's example id): the FE-02 zod mirror validates
// bookId as z.uuid(), so this suite's fixture must satisfy the same wire
// contract the backend's public identifiers do (AGENTS.md §6).
const BOOK_ID = '3fa85f64-5717-4562-b3fc-2c963f66afa6'

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
} as const satisfies BookResponse

const OUT_OF_STOCK_BOOK = {
  ...CLEAN_CODE,
  availability: 'OUT_OF_STOCK',
  stockQuantity: 0,
} as const satisfies BookResponse

const CART = {
  items: [
    {
      bookId: BOOK_ID,
      title: 'Clean Code',
      author: 'Robert C. Martin',
      coverUrl: CLEAN_CODE.coverUrl,
      unitPrice: 31.99,
      quantity: 1,
      lineTotal: 31.99,
      stockQuantity: 12,
      available: true,
      insufficientStock: false,
    },
  ],
  total: 31.99,
  currency: 'EUR',
} as const

// The 422/404/500 ProblemDetails verbatim from plan §2's shapes — the
// control classifies by STATUS and acts on the extra properties, never on
// the server's prose.
const INSUFFICIENT_STOCK_PROBLEM = {
  type: 'urn:foley-books:problem:insufficient-stock',
  title: 'Insufficient stock',
  status: 422,
  detail: 'Only 3 units left.',
  traceId: 'b2c3d4e5',
  availableStock: 3,
} as const

const BOOK_NOT_FOUND_PROBLEM = {
  type: 'urn:foley-books:problem:book-not-found',
  title: 'Book not found',
  status: 404,
  detail: 'No book exists with the given id.',
  traceId: 'd4e5f6a7',
} as const

const SERVER_ERROR_PROBLEM = {
  type: 'urn:foley-books:problem:error',
  title: 'Internal server error',
  status: 500,
  detail: 'Unexpected failure.',
  traceId: 'f6a7b8c9',
} as const

const USER = {
  id: 'u-1',
  email: 'reader@example.com',
  role: 'CUSTOMER',
} as const

function installStub(responder: Responder): SentRequest[] {
  const sent: SentRequest[] = []
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    const request: OutgoingRequest = {
      method: (config.method ?? 'get').toUpperCase(),
      url: config.url ?? '',
    }
    const body =
      typeof config.data === 'string' && config.data.length > 0
        ? (JSON.parse(config.data) as unknown)
        : undefined
    sent.push({ ...request, body })
    const [status, data] = responder(request)
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

function renderAdd(
  book: BookResponse = CLEAN_CODE,
  withCartProbe = false,
): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/books/${BOOK_ID}`]}>
          <Routes>
            <Route
              path="/books/:id"
              element={<AddToCartButton book={book} />}
            />
            <Route path="/login" element={<p>login landing</p>} />
          </Routes>
          <LocationProbe />
          {withCartProbe ? <CartProbe /> : null}
        </MemoryRouter>
      </QueryClientProvider>
    </AuthProvider>,
  )
}

/** Live route + carried router state, so the LC-27 return path is pinned
 *  through visible location state rather than component internals. */
function LocationProbe() {
  const location = useLocation()
  const from = (location.state as LoginRedirectState | null)?.from ?? 'none'
  return <p>{`route: ${location.pathname} from: ${from}`}</p>
}

/** Stand-in for FE-15's cart read: mounted on the same ['cart'] key the
 *  successful add must invalidate. */
function CartProbe() {
  const { data } = useQuery({ queryKey: ['cart'], queryFn: getCart })
  return <p>cart lines: {data?.items.length ?? 0}</p>
}

function signIn(): void {
  setTokens({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    user: USER,
  })
}

async function clickAdd(): Promise<void> {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Add to cart' }))
}

beforeEach(() => {
  // Same store normalization as the Header/LoginForm suites:
  // setTokens-then-clearTokens also resets the LC-07 flag that clearTokens
  // alone deliberately preserves.
  setTokens({ accessToken: 'reset', refreshToken: 'reset' })
  clearTokens()
  setSessionExpiredHandler(null)
  localStorage.clear()
})

describe('AddToCartButton', () => {
  it('addToCartButton_whenAnonymous_showsSignInPromptNotAddControls', () => {
    // FR-10: "Anonymous visitor attempting to add … is prompted to log in
    // (no guest cart)" — the prompt is guidance (role="status"), and neither
    // the quantity selector nor the add button exists to attempt with.
    installStub(() => [201, CART])
    renderAdd()

    expect(screen.getByRole('status')).toHaveTextContent(
      'Sign in to start your cart',
    )
    expect(screen.queryByRole('button', { name: 'Add to cart' })).toBeNull()
    expect(screen.queryByLabelText('Quantity')).toBeNull()
  })

  it('addToCartButton_whenPromptSignInClicked_goesToLoginCarryingTheBookPath', async () => {
    // LC-27's return-path contract reused verbatim: LoginPage replays the
    // same LoginRedirectState the RequireAuth guard sends, so signing in
    // lands the reader back on the book they were adding.
    const user = userEvent.setup()
    installStub(() => [201, CART])
    renderAdd()

    await user.click(
      screen.getByRole('link', { name: 'Sign in to add to cart' }),
    )

    expect(
      await screen.findByText(`route: /login from: /books/${BOOK_ID}`),
    ).toBeInTheDocument()
  })

  it('addToCartButton_whenSignedInAddClicked_postsBookIdWithDefaultQuantity', async () => {
    // FR-10: default quantity is 1; the payload is the AddItemRequest the
    // zod mirror (FE-02) guarantees, posted to /cart/items through the
    // shared authenticated instance.
    const sent = installStub(() => [201, CART])
    signIn()
    renderAdd()

    await clickAdd()

    expect(await screen.findByText(/Added to your cart/)).toBeInTheDocument()
    expect(sent).toEqual([
      {
        method: 'POST',
        url: '/cart/items',
        body: { bookId: BOOK_ID, quantity: 1 },
      },
    ])
    expect(screen.getByRole('link', { name: 'View cart' })).toHaveAttribute(
      'href',
      '/cart',
    )
  })

  it('addToCartButton_whenQuantityRaisedThenAdded_postsChosenQuantityAndResets', async () => {
    // FR-10 "quantity is selectable at add time": the stepper's ceiling is
    // this book's live stock, the chosen number is what posts, and a
    // successful add resets the default for the next one.
    const user = userEvent.setup()
    const sent = installStub(() => [201, CART])
    signIn()
    renderAdd()

    expect(screen.getByLabelText('Quantity')).toHaveAttribute('max', '12')
    await user.click(screen.getByRole('button', { name: 'Increase quantity' }))
    await user.click(screen.getByRole('button', { name: 'Increase quantity' }))
    await user.click(screen.getByRole('button', { name: 'Add to cart' }))

    expect(sent).toEqual([
      {
        method: 'POST',
        url: '/cart/items',
        body: { bookId: BOOK_ID, quantity: 3 },
      },
    ])
    await screen.findByText(/Added to your cart/)
    expect(screen.getByLabelText('Quantity')).toHaveValue(1)
  })

  it('addToCartButton_whenOutOfStock_showsNoticeAndCannotAdd', async () => {
    // FR-10 "out-of-stock books cannot be added": the gate is stock, not
    // session — even signed in there is no route to an add, and clicking
    // the disabled button posts nothing.
    const sent = installStub(() => [201, CART])
    signIn()
    renderAdd(OUT_OF_STOCK_BOOK)

    expect(screen.getByRole('status')).toHaveTextContent(
      'This title is currently out of stock. Check back soon.',
    )
    expect(screen.queryByLabelText('Quantity')).toBeNull()
    await clickAdd()
    expect(sent).toHaveLength(0)
  })

  it('addToCartButton_whenAnonymousViewsOutOfStock_showsStockNoticeNotLoginPrompt', () => {
    // Pins the branch precedence the control chose for FR-10's two gates:
    // stock before session. Luring a visitor into signing in for a book
    // nobody can add would be a prompt the spec never asked for.
    installStub(() => [201, CART])
    renderAdd(OUT_OF_STOCK_BOOK)

    expect(screen.getByRole('status')).toHaveTextContent(
      'This title is currently out of stock. Check back soon.',
    )
    expect(
      screen.queryByRole('link', { name: 'Sign in to add to cart' }),
    ).toBeNull()
  })

  it('addToCartButton_whenServerReportsInsufficientStock_showsServersAvailableStock', async () => {
    // LC-12: the rejection shows the available stock — and it shows the
    // number from the 422's ProblemDetail, not the page's stale copy.
    installStub(() => [422, INSUFFICIENT_STOCK_PROBLEM])
    signIn()
    renderAdd()

    await clickAdd()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Only 3 units are still available.')
    expect(screen.queryByText(/Added to your cart/)).toBeNull()
  })

  it('addToCartButton_whenSingleBookAnsweredFourOhFour_showsCatalogGoneAlert', async () => {
    // A 404 from the add is the vanished-title verdict (plan §2): a
    // different string from the transport failure, and still no success.
    installStub(() => [404, BOOK_NOT_FOUND_PROBLEM])
    signIn()
    renderAdd()

    await clickAdd()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This book is no longer in the catalog.',
    )
  })

  it('addToCartButton_whenServiceFails_showsGenericAlertWithTraceId', async () => {
    // NFR-06/D-15: anything uninterpretable collapses to one generic
    // failure plus the traceable identifier.
    installStub(() => [500, SERVER_ERROR_PROBLEM])
    signIn()
    renderAdd()

    await clickAdd()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('We could not update your cart.')
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')
  })

  it('add_whenRequestPending_announcesBusyAndRejectsSecondActivation', async () => {
    // NFR-03: the in-flight add is visible (busy button) and announced
    // (sr-only status); a second activation cannot post a duplicate line
    // bump (FR-10's summing is the server's, never a client double-fire).
    signIn()
    const sent: SentRequest[] = []
    let settle: ((response: AxiosResponse) => void) | undefined
    http.defaults.adapter = (config: InternalAxiosRequestConfig) => {
      sent.push({
        method: (config.method ?? 'post').toUpperCase(),
        url: config.url ?? '',
      })
      return new Promise<AxiosResponse>((resolve) => {
        settle = resolve
      })
    }
    const user = userEvent.setup()
    renderAdd()

    await user.click(screen.getByRole('button', { name: 'Add to cart' }))

    const busy = await screen.findByRole('button', { name: 'Adding…' })
    expect(busy).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('Adding to cart…')

    await user.click(busy)
    expect(sent).toHaveLength(1)

    settle?.({
      status: 201,
      statusText: '',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
      data: CART,
    })
    expect(await screen.findByText(/Added to your cart/)).toBeInTheDocument()
    // The busy announcement is gone; the only status left is the success.
    expect(screen.queryByText('Adding to cart…')).toBeNull()
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  it('addToCartButton_whenAddSucceeded_refetchesCartForFreshTotals', async () => {
    // D-08's client half: totals are server-computed, so every successful
    // write invalidates the cart query and any mounted cart read refetches.
    const sent = installStub((request) =>
      request.method === 'POST' ? [201, CART] : [200, CART],
    )
    signIn()
    renderAdd(CLEAN_CODE, true)

    expect(await screen.findByText('cart lines: 1')).toBeInTheDocument()
    await clickAdd()

    await waitFor(() =>
      expect(sent.map((request) => `${request.method} ${request.url}`)).toEqual(
        ['GET /cart', 'POST /cart/items', 'GET /cart'],
      ),
    )
  })
})
