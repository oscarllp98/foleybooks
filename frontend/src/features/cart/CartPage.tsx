// FE-15 (FR-11, FR-12, FR-13): the real cart page — this replaced FE-10's
// guarded landing stub. The read and the two write verbs live in
// hooks/useCart (plan §1) over the one ['cart'] cache AddToCartButton also
// invalidates, so every write lands here as the server's fresh truth. FR-11
// lines show cover, title, unit price, quantity and line total; the grand
// total is rendered verbatim from the response — D-08/NFR-07: every number
// here comes from order-service, this component never adds, multiplies or
// subtracts money. The catalog-sourced fields of a flagged line arrive null
// and render as a deliberate "—", the honest encoding of "there is nothing
// to show" (LC-14).
//
// FR-12 lives in the line's QuantitySelector (FE-05, min 0): positive
// changes PATCH /cart/items/{bookId} and render the CartResponse the PATCH
// answers — ADR-010: the 200 body IS the new cart view, so the client never
// refetches a successful change. A decrease to exactly 0 routes to DELETE
// (FR-13's verb, idempotent 204 — no body, so the fresh view arrives by
// refetch), because that is a removal request, not a quantity: ADR-004
// stores no zeros and ADR-010 keeps that route catalog-free, precisely so
// flagged lines stay clearable. Above-stock requests are rejected 422 with
// availableStock, and LC-12's rule — the rejection shows the available
// stock — is honored with the SERVER's number from the ProblemDetail, never
// the page's possibly stale copy.
//
// The two flags FR-11 re-validates on every read render here (LC-30:
// "insufficient" names the server's stock and the user can act on the line;
// LC-14: "unavailable" vanishes the catalog fields). A failed write is
// feedback, never a silent no-op: 422 and 404 are classified by status (the
// 404 on a write means the view is stale — the response is to show the
// server's current cart again), everything else collapses to the generic
// failure plus the ProblemDetail traceId (NFR-06, D-15). NFR-03 states
// complete the page: spinner while loading, error state with retry, and the
// empty cart as a friendly empty state with a browse CTA.

import { isAxiosError } from 'axios'
import { useState } from 'react'
import { Link } from 'react-router'
import { EmptyState } from '../../components/EmptyState'
import { ErrorState } from '../../components/ErrorState'
import { QuantitySelector } from '../../components/QuantitySelector'
import { Spinner } from '../../components/Spinner'
import {
  useCartMutations,
  useCartQuery,
  type QuantityChange,
} from '../../hooks/useCart'
import { formatEur } from '../../lib/money'
import type { CartItemResponse, CartResponse } from '../../types/cart'

/** The ProblemDetail properties this view can act on (D-15). */
interface CartProblem {
  status?: number
  availableStock?: number
  traceId?: string
}

/** The page-level verdict of one failed write, classified from the
 *  server's status. */
type WriteVerdict =
  | { kind: 'insufficient'; availableStock: number }
  | { kind: 'stale' }
  | { kind: 'failure'; traceId?: string }

// No live stock to show as a ceiling (an LC-14 line has none): the FE-05
// selector's static default stays in charge, and the server's 1..stock
// bound is the 422 — D-08: the widget bounds itself, the verdict is the
// service's.
const NO_STOCK_BOUND = 999

// LC-06's lesson applied to the cart: a transport failure never borrows the
// vocabulary of a business rejection — one generic string for everything
// this page cannot interpret.
const CART_FAILURE = 'We could not update your cart. Please try again.'

function classifyWriteFailure(error: unknown): WriteVerdict {
  if (!isAxiosError<CartProblem>(error)) return { kind: 'failure' }
  const status = error.response?.status
  const problem = error.response?.data
  const availableStock = problem?.availableStock
  if (status === 422 && typeof availableStock === 'number') {
    return { kind: 'insufficient', availableStock }
  }
  // ADR-010: a 404 on a write — vanished line (cart-line-not-found) or
  // vanished book behind an existing line (book-not-found) — means "your
  // view is stale", not "you hit a bug". Say it, and re-show the server's
  // current cart.
  if (status === 404) return { kind: 'stale' }
  return { kind: 'failure', traceId: problem?.traceId }
}

function problemTraceId(error: unknown): string | undefined {
  if (!isAxiosError<CartProblem>(error)) return undefined
  return error.response?.data?.traceId
}

/**
 * FE-15 (FR-11, FR-12, FR-13): the /cart page body, mounted behind FE-10's
 * auth guard — the identity rides the JWT, never a parameter (C26).
 */
export function CartPage() {
  // One write at a time, one banner at a time: the notice belongs to the
  // page, not the line — a write that ends in the line vanishing (404 →
  // fresh empty read) must still be able to tell the user why the view
  // moved under them.
  const [feedback, setFeedback] = useState<
    (WriteVerdict & { bookName: string }) | null
  >(null)

  const { data, isPending, isError, error, refetch } = useCartQuery()

  const reportFailure = (
    writeError: unknown,
    { bookName }: QuantityChange,
  ): void => {
    setFeedback({ ...classifyWriteFailure(writeError), bookName })
  }

  const { changeQuantity, removeLine } = useCartMutations({
    // A committed write answers the previous verdict: the success is the
    // visible response, no banner text has to survive it.
    onWriteSuccess: () => {
      setFeedback(null)
    },
    onWriteError: reportFailure,
  })

  const writeInFlight = changeQuantity.isPending || removeLine.isPending

  const handleQuantityChange = (change: QuantityChange): void => {
    if (writeInFlight) return
    // A new activation dismisses the previous verdict: the user is asking
    // again, the old answer no longer describes this one.
    setFeedback(null)
    // A decrease to exactly 0 is FR-13's removal, not a quantity (FR-12:
    // "setting quantity to 0 explicitly removes the line").
    if (change.quantity === 0) {
      removeLine.mutate(change)
    } else {
      changeQuantity.mutate(change)
    }
  }

  const heading = (
    <h1
      id="cart-heading"
      className="text-2xl font-bold tracking-tight text-neutral-900"
    >
      Your cart
    </h1>
  )

  // The page-level verdict of the last failed write (NFR-06): role="alert"
  // announces it, the book name says which line it is about, and it stays
  // rendered even after a stale-view refetch empties or rewrites the list.
  const banner =
    feedback === null ? null : (
      <p role="alert" className="text-sm font-medium text-red-700">
        <FeedbackSentence feedback={feedback} />
      </p>
    )

  if (isPending) {
    return (
      <section aria-labelledby="cart-heading" className="flex flex-col gap-4">
        {heading}
        <Spinner label="Loading your cart" />
      </section>
    )
  }

  if (isError || data === undefined) {
    return (
      <section aria-labelledby="cart-heading" className="flex flex-col gap-4">
        {heading}
        <ErrorState
          title="We could not load your cart"
          message="The order service did not answer. Please try again."
          traceId={problemTraceId(error)}
          onRetry={() => {
            void refetch()
          }}
        />
      </section>
    )
  }

  if (data.items.length === 0) {
    // FR-11: the empty cart is a friendly empty state with a call to action,
    // never an error (NFR-03).
    return (
      <section aria-labelledby="cart-heading" className="flex flex-col gap-4">
        {heading}
        {banner}
        <EmptyState
          title="Your cart is empty"
          message="Nothing in the cart yet. The books you add will wait for you here — they follow your account from device to device."
        >
          <Link
            to="/"
            className="rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
          >
            Browse books
          </Link>
        </EmptyState>
      </section>
    )
  }

  const cartView = (
    <div className="flex flex-col gap-6">
      <ul role="list" className="flex flex-col divide-y divide-neutral-200">
        {data.items.map((item) => (
          <li key={item.bookId}>
            <CartLine
              item={item}
              disabled={writeInFlight}
              onChange={handleQuantityChange}
            />
          </li>
        ))}
      </ul>
      <CartTotals cart={data} />
    </div>
  )

  return (
    <section aria-labelledby="cart-heading" className="flex flex-col gap-4">
      {heading}
      {writeInFlight ? (
        // NFR-03/NFR-04 (AddToCartButton pattern): the in-flight write is
        // announced, not only visible on the disabled controls. The lines
        // stay readable while the server recomputes — only activation is
        // blocked, so a second write cannot interleave into a view that
        // never was.
        <span role="status" aria-live="polite" className="sr-only">
          Updating your cart…
        </span>
      ) : null}
      {banner}
      {cartView}
    </section>
  )
}

function FeedbackSentence({
  feedback,
}: {
  feedback: WriteVerdict & { bookName: string }
}) {
  switch (feedback.kind) {
    case 'insufficient':
      // LC-12: the rejection shows the available stock — the SERVER's number
      // from the 422, not the page's possibly stale copy.
      return (
        <>
          {`Only ${feedback.availableStock} ${
            feedback.availableStock === 1 ? 'unit is' : 'units are'
          } still available for ${feedback.bookName}.`}
        </>
      )
    case 'stale':
      return <>{`${feedback.bookName}: that line changed on the server. Your cart now shows its latest state.`}</>
    case 'failure':
      // NFR-06: generic copy plus the traceable identifier; internal
      // details stay in the service logs.
      return (
        <>
          {CART_FAILURE}
          {feedback.traceId ? ` Reference: ${feedback.traceId}` : ''}
        </>
      )
  }
}

interface CartLineProps {
  item: CartItemResponse
  disabled: boolean
  onChange: (change: QuantityChange) => void
}

function CartLine({ item, disabled, onChange }: CartLineProps) {
  // LC-14: catalog-sourced fields are null on an unavailable line; the
  // title falls back to a neutral label so controls keep accessible names.
  const name = item.title ?? 'this book'
  const lineId = `cart-line-${item.bookId}`

  return (
    <article
      aria-labelledby={lineId}
      className="flex flex-wrap items-center gap-4 py-4"
    >
      <CartLineCover title={name} coverUrl={item.coverUrl} />
      <div className="flex min-w-40 flex-1 flex-col gap-1">
        <h2 id={lineId} className="font-semibold text-neutral-900">
          {item.title ?? 'This book is no longer in the catalog'}
        </h2>
        {item.author ? (
          <p className="text-sm text-neutral-600">{item.author}</p>
        ) : null}
        <p className="text-sm text-neutral-500">
          {`Unit price: ${item.unitPrice === null ? '—' : formatEur(item.unitPrice)}`}
        </p>
        {!item.available ? (
          <p role="status" className="text-sm font-medium text-red-700">
            This book is no longer in the catalog. Remove it to clear your
            cart.
          </p>
        ) : null}
        {item.available && item.insufficientStock ? (
          // LC-30: flagged at the server's read, worded with the SERVER's
          // stock, and actionable — update within the live bound or remove.
          <p role="status" className="text-sm font-medium text-amber-700">
            {`Only ${item.stockQuantity ?? 0} ${
              item.stockQuantity === 1 ? 'unit is' : 'units are'
            } left. Update the quantity or remove this line.`}
          </p>
        ) : null}
      </div>
      <QuantitySelector
        value={item.quantity}
        label={`quantity for ${name}`}
        min={0}
        // The selector's ceiling is the live stock the cart read carried
        // (LC-16); an unavailable line has no bound to show, so the write
        // path stops at its 404 and the remove button stays usable.
        max={item.stockQuantity ?? NO_STOCK_BOUND}
        disabled={disabled}
        onChange={(quantity) => {
          onChange({ bookId: item.bookId, bookName: name, quantity })
        }}
      />
      <p className="w-24 text-right text-sm font-semibold text-neutral-900">
        {item.lineTotal === null ? '—' : formatEur(item.lineTotal)}
      </p>
      <button
        type="button"
        onClick={() => {
          onChange({ bookId: item.bookId, bookName: name, quantity: 0 })
        }}
        disabled={disabled}
        className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm font-medium text-neutral-700 enabled:hover:border-red-300 enabled:hover:bg-red-50 enabled:hover:text-red-700 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
      >
        Remove {name} from cart
      </button>
    </article>
  )
}

/** FR-11 cover with the LC-29/D-11 failure placeholder (FE-05 pattern). */
function CartLineCover({
  title,
  coverUrl,
}: {
  title: string
  coverUrl: string | null
}) {
  const [coverFailed, setCoverFailed] = useState(false)

  return (
    <figure className="aspect-2/3 w-20 shrink-0 overflow-hidden rounded-md border border-neutral-200 bg-white">
      {coverUrl === null || coverFailed ? (
        <div
          role="img"
          aria-label={`Cover image unavailable for ${title}`}
          className="flex h-full w-full items-center justify-center bg-neutral-100 p-1"
        >
          <figcaption className="text-center text-[10px] font-medium text-neutral-500">
            {title}
          </figcaption>
        </div>
      ) : (
        <img
          src={coverUrl}
          alt={`Cover of ${title}`}
          onError={() => setCoverFailed(true)}
          className="h-full w-full object-cover"
        />
      )}
    </figure>
  )
}

function CartTotals({ cart }: { cart: CartResponse }) {
  return (
    <section
      aria-labelledby="cart-totals-heading"
      className="flex items-baseline justify-between rounded-lg border border-neutral-200 bg-neutral-50 px-4 py-3"
    >
      <h2
        id="cart-totals-heading"
        className="text-sm font-semibold uppercase tracking-wide text-neutral-600"
      >
        Grand total
      </h2>
      <p className="text-lg font-bold text-neutral-900">
        {formatEur(cart.total)}
        <span className="ml-1 text-sm font-medium text-neutral-500">
          {cart.currency}
        </span>
      </p>
    </section>
  )
}
