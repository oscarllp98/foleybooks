import {
  AxiosError,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { CartPage } from './CartPage'
import { http } from '../../lib/http'
import { formatEur } from '../../lib/money'
import type { CartItemResponse, CartResponse } from '../../types/cart'

// FE-15 tests (FR-11, FR-12, FR-13, LC-12, LC-14, LC-30; plan §6.5 "Cart:
// server-rendered totals, quantity update incl. qty-0 removal, remove,
// insufficient flags"): driven over the real shared axios instance through a
// stubbed adapter — the seam AddToCartButton/BookList established — with a
// fresh no-retry QueryClient per render. Nothing inside CartPage is mocked:
// the cart read, the PATCH/DELETE writes, the ['cart'] cache and the
// ProblemDetail classification all run for real; every assertion is made
// through visible, accessible output.

interface SentRequest {
  method: string
  url: string
  body?: unknown
}

type Response = [number, unknown] | Promise<[number, unknown]>
type Responder = (request: SentRequest) => Response

// Real v4 UUIDs (plan §2's example shape): public identifiers are UUIDs
// (AGENTS.md §6), so the fixtures satisfy the wire contract.
const CLEAN_CODE_ID = 'bc2db0bf-4a42-4203-bbbf-0dc433a6ac24'
const DUNE_ID = '5680880f-04c9-4be9-ad6d-18e8a7481759'
const VANISHED_ID = '5d30eb60-9952-44de-833f-c41b35d93eec'

function line(
  overrides: Partial<CartItemResponse> & Pick<CartItemResponse, 'bookId'>,
): CartItemResponse {
  return {
    title: 'Clean Code',
    author: 'Robert C. Martin',
    coverUrl: 'https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg',
    unitPrice: 31.99,
    quantity: 2,
    lineTotal: 63.98,
    stockQuantity: 12,
    available: true,
    insufficientStock: false,
    ...overrides,
  }
}

// plan §2's cart example in shape: one line, server-computed total — the
// line total and the grand total are the SAME server number.
const BASE_CART: CartResponse = {
  items: [line({ bookId: CLEAN_CODE_ID })],
  total: 63.98,
  currency: 'EUR',
}

const EMPTY_CART: CartResponse = { items: [], total: 0, currency: 'EUR' }

// FR-12's answer: the PATCH returns the full CartResponse (ADR-010) — Clean
// Code raised to 3, totals recomputed by order-service, not here.
const RAISED_CART: CartResponse = {
  items: [line({ bookId: CLEAN_CODE_ID, quantity: 3, lineTotal: 95.97 })],
  total: 95.97,
  currency: 'EUR',
}

// The server's view after the first decrease of BASE_CART: one unit.
const SINGLE_UNIT_CART: CartResponse = {
  items: [line({ bookId: CLEAN_CODE_ID, quantity: 1, lineTotal: 31.99 })],
  total: 31.99,
  currency: 'EUR',
}

// FR-11 re-validation on one read, carrying both flags: the LC-30 line is
// excluded from the server's total (D-10 — the grand total is only Clean
// Code's 63.98), the LC-14 line carries null catalog fields (ADR-005).
const FLAGGED_CART: CartResponse = {
  items: [
    line({ bookId: CLEAN_CODE_ID }),
    line({
      bookId: DUNE_ID,
      title: 'Dune',
      author: 'Frank Herbert',
      coverUrl: 'https://covers.openlibrary.org/b/isbn/9780441013593-L.jpg',
      unitPrice: 9.5,
      quantity: 5,
      lineTotal: 47.5,
      // The LC-30 shape: live stock below the line quantity. The selector
      // clamps its widget to the stock (LC-16), so the stepper reads 3.
      stockQuantity: 3,
      insufficientStock: true,
    }),
    line({
      bookId: VANISHED_ID,
      title: null,
      author: null,
      coverUrl: null,
      unitPrice: null,
      quantity: 1,
      lineTotal: null,
      stockQuantity: null,
      available: false,
    }),
  ],
  total: 63.98,
  currency: 'EUR',
}

// ProblemDetail fixtures in plan §2's shapes — the page classifies by STATUS
// and the extra properties, never by the server's prose.
const INSUFFICIENT_STOCK_PROBLEM = {
  type: 'urn:foley-books:problem:insufficient-stock',
  title: 'Insufficient stock',
  status: 422,
  detail: 'Only 3 units left.',
  traceId: 'b2c3d4e5',
  availableStock: 3,
} as const

const LINE_NOT_FOUND_PROBLEM = {
  type: 'urn:foley-books:problem:cart-line-not-found',
  title: 'Cart line not found',
  status: 404,
  detail: 'The cart has no line for the given book.',
  traceId: 'd4e5f6a7',
  bookId: CLEAN_CODE_ID,
} as const

const SERVER_ERROR_PROBLEM = {
  type: 'urn:foley-books:problem:error',
  title: 'Internal server error',
  status: 500,
  detail: 'Unexpected failure.',
  traceId: 'f6a7b8c9',
} as const

function installStub(responder: Responder): SentRequest[] {
  const sent: SentRequest[] = []
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    const request: SentRequest = {
      method: (config.method ?? 'get').toUpperCase(),
      url: config.url ?? '',
      body:
        typeof config.data === 'string' && config.data.length > 0
          ? (JSON.parse(config.data) as unknown)
          : undefined,
    }
    sent.push(request)
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
  return sent
}

function renderCartPage(responder: Responder): SentRequest[] {
  const sent = installStub(responder)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/cart']}>
        <CartPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return sent
}

async function renderSettled(responder: Responder): Promise<SentRequest[]> {
  const sent = renderCartPage(responder)
  expect(await screen.findByText('Clean Code')).toBeInTheDocument()
  return sent
}

function quantityInput(name: string): HTMLElement {
  return screen.getByRole('spinbutton', { name: `quantity for ${name}` })
}

// QuantitySelector builds the stepper buttons' accessible names from the
// lowercased label (FE-05), so the helper mirrors that spelling exactly.
function stepperName(name: string): string {
  return `quantity for ${name}`.toLowerCase()
}

function increaseButton(name: string): HTMLElement {
  return screen.getByRole('button', {
    name: `Increase ${stepperName(name)}`,
  })
}

function decreaseButton(name: string): HTMLElement {
  return screen.getByRole('button', {
    name: `Decrease ${stepperName(name)}`,
  })
}

function removeButton(name: string): HTMLElement {
  return screen.getByRole('button', { name: `Remove ${name} from cart` })
}

function requests(sent: SentRequest[], method: string): SentRequest[] {
  return sent.filter((request) => request.method === method)
}

/** Waits until the input shows `value` — the cache-to-control round trip
 *  after a write is asynchronous by nature (query settle + rerender). */
async function waitForValue(
  input: HTMLElement,
  value: number,
): Promise<HTMLElement> {
  await waitFor(() => expect(input).toHaveValue(value))
  return input
}

/** Method-aware responder with per-method call counters, so a test can hand
 *  a different body to the first read and the post-write refetch without
 *  touching the recorded list. */
function countingResponder(handlers: {
  GET?: (index: number) => [number, unknown]
  PATCH?: (index: number) => [number, unknown]
  DELETE?: (index: number) => [number, unknown]
}): Responder {
  const counts: Record<string, number> = { GET: 0, PATCH: 0, DELETE: 0 }
  return (request) => {
    const handler = handlers[request.method as keyof typeof handlers]
    const index = counts[request.method] ?? 0
    counts[request.method] = index + 1
    return handler
      ? handler(index)
      : [500, { type: 'urn:foley-books:problem:error', status: 500 }]
  }
}

describe('CartPage', () => {
  beforeEach(() => {
    http.defaults.adapter = undefined
  })

  it('cartPage_whenCartArrives_rendersLinesWithServerComputedTotals', async () => {
    // FR-11 + NFR-07/D-08: cover, title, unit price, quantity and line total
    // per line, the grand total verbatim from the response. The line total
    // and the grand total are the SAME number the server sent — the page
    // renders money, it never computes it.
    await renderSettled(() => [200, BASE_CART])

    expect(
      screen.getByRole('heading', { level: 1, name: 'Your cart' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('img', { name: 'Cover of Clean Code' }),
    ).toHaveAttribute(
      'src',
      'https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg',
    )
    expect(quantityInput('Clean Code')).toHaveValue(2)
    expect(
      screen.getByText(`Unit price: ${formatEur(31.99)}`),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: 'Grand total' }),
    ).toBeInTheDocument()
    expect(screen.getAllByText(formatEur(63.98))).toHaveLength(2)
  })

  it('cartPage_whenCartIsEmpty_rendersFriendlyEmptyStateWithBrowseCallToAction', async () => {
    // FR-11: the empty cart is nothing-to-show with a browse CTA, never an
    // error (NFR-03).
    renderCartPage(() => [200, EMPTY_CART])

    const empty = await screen.findByRole('region', {
      name: 'Your cart is empty',
    })
    expect(
      within(empty).getByRole('link', { name: 'Browse books' }),
    ).toHaveAttribute('href', '/')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
  })

  it('cartPage_whileLoading_showsAnnouncedSpinnerThenLines', async () => {
    // NFR-03: the read is announced and visibly pending before content
    // arrives — the guarded page is never a silent blank.
    let settle: ((pair: [number, unknown]) => void) | undefined
    renderCartPage(
      () =>
        new Promise<[number, unknown]>((resolve) => {
          settle = resolve
        }),
    )

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Loading your cart',
    )
    expect(screen.queryByText('Clean Code')).toBeNull()

    settle?.([200, BASE_CART])
    expect(await screen.findByText('Clean Code')).toBeInTheDocument()
    expect(screen.queryByText('Loading your cart')).toBeNull()
  })

  it('cartPage_whenReadFails_showsErrorStateWithTraceIdAndRetryRecovers', async () => {
    // NFR-06/D-15 + NFR-03: the failure state names itself, carries the
    // ProblemDetail traceId, and its retry button re-issues the read.
    let readAttempts = 0
    const sent = renderCartPage(() => {
      readAttempts += 1
      return readAttempts === 1
        ? [500, SERVER_ERROR_PROBLEM]
        : [200, BASE_CART]
    })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('We could not load your cart')
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Clean Code')).toBeInTheDocument()
    expect(requests(sent, 'GET')).toHaveLength(2)
  })

  it('cartPage_whenQuantityRaised_patchesTheLineAndRendersTheServersAnswerWithoutRefetch', async () => {
    // FR-12 within 1..stock: PATCH /cart/items/{bookId} with the SET value,
    // and ADR-010's write contract — the 200 body IS the new view, so the
    // cache is replaced from it and no GET ever follows a successful write.
    const sent = await renderSettled(
      countingResponder({
        GET: () => [200, BASE_CART],
        PATCH: () => [200, RAISED_CART],
      }),
    )

    await userEvent.click(increaseButton('Clean Code'))

    expect(sent).toContainEqual({
      method: 'PATCH',
      url: `/cart/items/${CLEAN_CODE_ID}`,
      body: { quantity: 3 },
    })
    // The PATCH body's cart renders whole: line total AND grand total are
    // the server's 95.97 — two renderings of one server number, D-08.
    await waitFor(() =>
      expect(screen.getAllByText(formatEur(95.97))).toHaveLength(2),
    )
    expect(quantityInput('Clean Code')).toHaveValue(3)
    expect(requests(sent, 'GET')).toHaveLength(1)
  })

  it('cartPage_whenRemoveClicked_deletesLineAndRefetchesTheServerView', async () => {
    // FR-13: DELETE /cart/items/{bookId}; the 204 carries no body, so the
    // fresh view — recalculated totals included — arrives by refetch, never
    // by client-side subtraction (D-08).
    const sent = renderCartPage(
      countingResponder({
        GET: (index) => [200, index === 0 ? BASE_CART : EMPTY_CART],
        DELETE: () => [204, undefined],
      }),
    )

    await screen.findByText('Clean Code')
    await userEvent.click(removeButton('Clean Code'))

    expect(sent).toContainEqual({
      method: 'DELETE',
      url: `/cart/items/${CLEAN_CODE_ID}`,
      body: undefined,
    })
    expect(
      await screen.findByRole('region', { name: 'Your cart is empty' }),
    ).toBeInTheDocument()
    expect(requests(sent, 'GET')).toHaveLength(2)
  })

  it('cartPage_whenStepperDecreasesToOneThenZero_removesTheLineInsteadOfPatchingZero', async () => {
    // FR-12 "setting quantity to 0 explicitly removes the line" — routed to
    // FR-13's DELETE verb (ADR-004 stores no zeros): the second decrease
    // reaches 0 and the line disappears instead of PATCHing an illegal zero.
    const sent = renderCartPage(
      countingResponder({
        GET: (index) => [200, index === 0 ? BASE_CART : EMPTY_CART],
        PATCH: () => [200, SINGLE_UNIT_CART],
        DELETE: () => [204, undefined],
      }),
    )

    await screen.findByText('Clean Code')
    const user = userEvent.setup()
    await user.click(decreaseButton('Clean Code')) // 2 -> 1: a PATCH
    expect(await waitForValue(quantityInput('Clean Code'), 1)).toBeVisible()

    await user.click(decreaseButton('Clean Code')) // 1 -> 0: a removal

    expect(
      await screen.findByRole('region', { name: 'Your cart is empty' }),
    ).toBeInTheDocument()
    expect(sent).toContainEqual({
      method: 'DELETE',
      url: `/cart/items/${CLEAN_CODE_ID}`,
      body: undefined,
    })
    expect(requests(sent, 'PATCH')).toHaveLength(1)
  })

  it('cartPage_whenPatchAnsweredFourTwentyTwo_showsServersAvailableStockAndCartStands', async () => {
    // LC-12/LC-16: the rejection shows the available stock — the number from
    // the 422's ProblemDetail, not the page's copy (the fixture claims 12,
    // the server says 3, the alert says 3) — and nothing on the page moves.
    await renderSettled(
      countingResponder({
        GET: () => [200, BASE_CART],
        PATCH: () => [422, INSUFFICIENT_STOCK_PROBLEM],
      }),
    )

    await userEvent.click(increaseButton('Clean Code'))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'Only 3 units are still available for Clean Code.',
    )
    expect(quantityInput('Clean Code')).toHaveValue(2)
    expect(screen.getAllByText(formatEur(63.98))).toHaveLength(2)
  })

  it('cartPage_whenReadCarriesFlags_rendersBothNoticesAndFlaggedLinesStayRemovable', async () => {
    // FR-11 re-validation on every read: the LC-30 line names the SERVER's
    // stock as an actionable guidance notice and its stepper ceiling is that
    // stock (LC-16); the LC-14 line renders the vanished-catalog state with
    // "—" for the null fields; the grand total stays the server's own number
    // (flagged lines already excluded by order-service, D-10); ADR-010 keeps
    // both flagged lines removable through the catalog-free DELETE route.
    renderCartPage(() => [200, FLAGGED_CART])

    expect(await screen.findByText('Dune')).toBeInTheDocument()
    // role="status": a guidance notice, not an error — nothing the user did
    // this second was wrong; the stock moved underneath them.
    const insufficient = screen.getByText(/Only 3 units are left/)
    expect(insufficient).toHaveAttribute('role', 'status')
    // LC-16 bound: the stepper ceiling is the live stock, and at 5 > 3 even
    // the clamped widget cannot offer an increase.
    expect(quantityInput('Dune')).toHaveAttribute('max', '3')
    expect(increaseButton('Dune')).toBeDisabled()

    // LC-14: the vanished book renders its honest nulls.
    const unavailableLine = screen.getByRole('article', {
      name: 'This book is no longer in the catalog',
    })
    expect(
      within(unavailableLine).getByText('Unit price: —'),
    ).toBeInTheDocument()
    expect(
      within(unavailableLine).getByText(
        'This book is no longer in the catalog. Remove it to clear your cart.',
      ),
    ).toBeInTheDocument()

    const totals = screen.getByRole('heading', { name: 'Grand total' })
    expect(
      within((totals.closest('section') ?? totals) as HTMLElement).getByText(
        formatEur(63.98),
      ),
    ).toBeInTheDocument()

    expect(removeButton('Clean Code')).toBeEnabled()
    expect(removeButton('Dune')).toBeEnabled()
    expect(removeButton('this book')).toBeEnabled()
  })

  it('cartPage_whenWriteAnswersFourOhFour_flagsTheStaleViewAndShowsTheFreshRead', async () => {
    // ADR-010: a 404 on a write — vanished line or vanished book behind it —
    // means "your view is stale", not "you hit a bug": say so, re-read, and
    // the emptied server truth replaces the dead line.
    const sent = renderCartPage(
      countingResponder({
        GET: (index) => [200, index === 0 ? BASE_CART : EMPTY_CART],
        PATCH: () => [404, LINE_NOT_FOUND_PROBLEM],
      }),
    )

    await screen.findByText('Clean Code')
    await userEvent.click(increaseButton('Clean Code'))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Clean Code: that line changed on the server.',
    )
    expect(
      await screen.findByRole('region', { name: 'Your cart is empty' }),
    ).toBeInTheDocument()
    expect(requests(sent, 'GET')).toHaveLength(2)
  })

  it('cartPage_whenWriteFailsGenerically_showsGenericAlertWithTraceId', async () => {
    // NFR-06: anything uninterpretable collapses to one generic failure plus
    // the traceable identifier; the cart itself stays exactly as it was.
    await renderSettled(
      countingResponder({
        GET: () => [200, BASE_CART],
        PATCH: () => [500, SERVER_ERROR_PROBLEM],
      }),
    )

    await userEvent.click(increaseButton('Clean Code'))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('We could not update your cart.')
    expect(alert).toHaveTextContent('Reference: f6a7b8c9')
    expect(quantityInput('Clean Code')).toHaveValue(2)
  })

  it('cartPage_whenWriteIsInFlight_announcesBusyAndBlocksASecondWrite', async () => {
    // NFR-03: the in-flight write is announced and every control is
    // disabled (NFR-04: real disabled state, not only styling), so a second
    // activation cannot interleave a patch that never was. Settling renders
    // the PATCH body's cart — ADR-010: no refetch of a fresh fact.
    let writes = 0
    let settle: ((response: AxiosResponse) => void) | undefined
    http.defaults.adapter = (config: InternalAxiosRequestConfig) => {
      const method = (config.method ?? 'get').toUpperCase()
      if (method === 'PATCH') {
        writes += 1
        return new Promise<AxiosResponse>((resolve) => {
          settle = resolve
        })
      }
      return Promise.resolve({
        status: 200,
        statusText: '',
        headers: {},
        config,
        data: BASE_CART,
      })
    }
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/cart']}>
          <CartPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await screen.findByText('Clean Code')
    const user = userEvent.setup()
    await user.click(increaseButton('Clean Code'))

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Updating your cart…',
    )
    const busyIncrease = increaseButton('Clean Code')
    expect(busyIncrease).toBeDisabled()
    expect(quantityInput('Clean Code')).toBeDisabled()
    expect(removeButton('Clean Code')).toBeDisabled()
    // A forced activation against the disabled control is still not a write.
    fireEvent.click(busyIncrease)
    expect(writes).toBe(1)

    settle?.({
      status: 200,
      statusText: '',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
      data: RAISED_CART,
    })
    await waitFor(() =>
      expect(screen.getAllByText(formatEur(95.97))).toHaveLength(2),
    )
    expect(writes).toBe(1)
    expect(screen.queryByText('Updating your cart…')).toBeNull()
  })
})
