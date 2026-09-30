import { useMutation, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { addItem } from '../../api/orders'
import { QuantitySelector } from '../../components/QuantitySelector'
import { useAuth } from '../../hooks/useAuth'
import type { LoginRedirectState } from '../../types/routing'
import { addItemRequestSchema } from '../../types/cart'
import type { BookResponse } from '../../types/catalog'

// FE-14 (FR-10): the add-to-cart control BookDetail mounts under the
// metadata. FR-10 names three behaviors and this component owns exactly
// those, nothing more: (1) quantity is selectable at add time, default 1,
// bounded to 1..stock — FE-05's QuantitySelector enforces the static bound
// client-side, but the live stock verdict is the server's 422 (D-08: the
// frontend renders, never decides); (2) out-of-stock books cannot be added
// at all; (3) an anonymous visitor is prompted to log in — there is no
// guest cart (C26's identity rides the JWT). The POST itself goes through
// api/orders (C10) via TanStack Query, the app's only server-state door
// (D-13), and a successful add invalidates the ['cart'] query so FE-15's
// cart reads always show the server-computed truth.
//
// The three server verdicts this control can act on (plan §2): 422
// insufficient-stock carries `availableStock` (LC-12 — rejection that SHOWS
// the stock), 404 book-not-found is the vanished-title state, and anything
// else collapses to the generic failure plus the ProblemDetail traceId
// (NFR-06, D-15). Success is announced, never silent (NFR-03).

/** The ProblemDetail properties this control can act on (D-15). */
interface AddProblem {
  status?: number
  availableStock?: number
  traceId?: string
}

/** The outcome of one add attempt, classified from the server's verdict. */
type Feedback =
  | { kind: 'added' }
  | { kind: 'insufficient'; availableStock: number }
  | { kind: 'disappeared' }
  | { kind: 'failure'; traceId?: string }

// LC-06's lesson applied to the cart: a transport failure never borrows the
// vocabulary of a business rejection — one generic string for everything
// this control cannot interpret.
const CART_FAILURE = 'We could not update your cart. Please try again.'

function classifyAddFailure(error: unknown): Feedback {
  if (!isAxiosError<AddProblem>(error)) return { kind: 'failure' }
  const status = error.response?.status
  const problem = error.response?.data
  const availableStock = problem?.availableStock
  if (status === 422 && typeof availableStock === 'number') {
    return { kind: 'insufficient', availableStock }
  }
  if (status === 404) return { kind: 'disappeared' }
  return { kind: 'failure', traceId: problem?.traceId }
}

interface AddToCartButtonProps {
  book: BookResponse
}

/**
 * FE-14 (FR-10, LC-12, LC-27): quantity-at-add-time control for one book,
 * rendered by BookDetail. Anonymous visitors get the login prompt instead of
 * the control — with the book page as the return path, so the FR-10 promise
 * "after logging in, the cart is available" continues from exactly where the
 * visitor was interrupted (the same LoginRedirectState contract RequireAuth
 * and LoginPage established).
 */
export function AddToCartButton({ book }: AddToCartButtonProps) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  const queryClient = useQueryClient()
  const [quantity, setQuantity] = useState(1)
  const [feedback, setFeedback] = useState<Feedback | null>(null)

  const mutation = useMutation({
    mutationFn: addItem,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['cart'] })
      setQuantity(1)
      setFeedback({ kind: 'added' })
    },
    onError: (error) => {
      setFeedback(classifyAddFailure(error))
    },
  })

  const handleAdd = (): void => {
    setFeedback(null)
    // The zod mirror (FE-02) keeps the wire shape honest; the 1..stock
    // verdict above it is the service's (FR-10/LC-13), re-checked by the
    // selector's ceiling and by the 422 if the client's stock is stale.
    mutation.mutate(addItemRequestSchema.parse({ bookId: book.id, quantity }))
  }

  // FR-10 gates out-of-stock before the login branch: a book nobody can buy
  // has no business offering a sign-in shortcut. The gate is stock, not
  // session — the same reading every other branch takes from the server's
  // derived availability (D-09).
  if (book.stockQuantity < 1) {
    return (
      <section aria-label="Add to cart" className="mt-4 flex flex-col gap-2">
        <p role="status" className="text-sm text-neutral-600">
          This title is currently out of stock. Check back soon.
        </p>
        <button
          type="button"
          disabled
          className="self-start rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white opacity-60 disabled:cursor-not-allowed"
        >
          Add to cart
        </button>
      </section>
    )
  }

  if (!isAuthenticated) {
    return (
      // FR-10/LC-27: no guest cart. A guidance state, not a failure — the
      // same amber role="status" panel LoginForm's LC-05 notice established,
      // because nothing the visitor did was wrong; they just need a session.
      <section aria-label="Add to cart">
        <div
          role="status"
          aria-labelledby="cart-login-prompt-heading"
          className="mt-4 flex flex-col gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3"
        >
          <h3
            id="cart-login-prompt-heading"
            className="text-sm font-semibold text-amber-800"
          >
            Sign in to start your cart
          </h3>
          <p className="text-sm text-amber-700">
            Carts live with your account, so they follow you from device to
            device and survive a reload. Sign in to add this book.
          </p>
          <Link
            to="/login"
            state={{ from: location.pathname } satisfies LoginRedirectState}
            className="self-start rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
          >
            Sign in to add to cart
          </Link>
        </div>
      </section>
    )
  }

  return (
    <section aria-label="Add to cart" className="mt-4 flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <QuantitySelector
          value={quantity}
          onChange={setQuantity}
          min={1}
          max={book.stockQuantity}
        />
        <button
          type="button"
          onClick={handleAdd}
          disabled={mutation.isPending}
          aria-busy={mutation.isPending}
          className="rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800 disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-neutral-700"
        >
          {mutation.isPending ? 'Adding…' : 'Add to cart'}
        </button>
      </div>

      {mutation.isPending ? (
        // NFR-03/NFR-04 (LoginForm pattern): the in-flight add is announced,
        // not only visible on the disabled button.
        <span role="status" aria-live="polite" className="sr-only">
          Adding to cart…
        </span>
      ) : null}

      {feedback?.kind === 'added' ? (
        <p role="status" className="text-sm font-medium text-green-700">
          Added to your cart.{' '}
          <Link
            to="/cart"
            className="underline hover:text-green-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-green-700"
          >
            View cart
          </Link>
        </p>
      ) : null}

      {feedback?.kind === 'insufficient' ? (
        // LC-12: the rejection shows the available stock — and it shows the
        // SERVER's number, the one that arrived in the 422, not the page's
        // possibly stale copy.
        <p role="alert" className="text-sm font-medium text-red-700">
          {`Only ${feedback.availableStock} ${
            feedback.availableStock === 1 ? 'unit is' : 'units are'
          } still available.`}
        </p>
      ) : null}

      {feedback?.kind === 'disappeared' ? (
        // Defensive (LC-14's catalog-side twin): the catalog is static in
        // the MVP, but a vanished id still gets a truthful verdict, not a
        // generic shrug.
        <p role="alert" className="text-sm font-medium text-red-700">
          This book is no longer in the catalog.
        </p>
      ) : null}

      {feedback?.kind === 'failure' ? (
        // NFR-06: generic copy plus the traceable identifier; internal
        // details stay in the service logs.
        <p role="alert" className="text-sm font-medium text-red-700">
          {CART_FAILURE}
          {feedback.traceId ? ` Reference: ${feedback.traceId}` : ''}
        </p>
      ) : null}
    </section>
  )
}
