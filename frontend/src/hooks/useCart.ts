import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import {
  getCart,
  removeItem,
  updateItemQuantity,
} from '../api/orders'
import { updateQuantityRequestSchema } from '../types/cart'
import type { CartResponse } from '../types/cart'

/**
 * FE-15 (plan §1 "hooks/ useCart"): the app's cart server-state door — the
 * TanStack Query wrapper the plan names. One ['cart'] cache exists: the read
 * here, CartPage's writes, and AddToCartButton's post-add invalidation all
 * address that key, so every component mounted on it sees the same
 * server-computed truth (D-08/D-13). The wire stays in api/orders (C10):
 * this module orchestrates cache semantics only, it performs no HTTP.
 */

export const CART_QUERY_KEY = ['cart'] as const

/** One line write: which line, the new quantity, and a display name the
 *  page's verdict banner can quote (the banner must survive the line
 *  disappearing from a stale-view refetch). */
export interface QuantityChange {
  bookId: string
  bookName: string
  quantity: number
}

export function useCartQuery() {
  return useQuery({ queryKey: CART_QUERY_KEY, queryFn: getCart })
}

interface CartMutationHandlers {
  onWriteSuccess: () => void
  onWriteError: (error: unknown, change: QuantityChange) => void
}

export function useCartMutations({
  onWriteSuccess,
  onWriteError,
}: CartMutationHandlers) {
  const queryClient = useQueryClient()

  const refreshAfterStaleWrite = (error: unknown): void => {
    // ADR-010: a 404 on a write — vanished line or vanished book behind it —
    // means the view is stale, not that a bug happened. The honest answer
    // is the fresh read, so the cache move lives with the cache.
    if (isAxiosError(error) && error.response?.status === 404) {
      invalidateCart(queryClient)
    }
  }

  const changeQuantity = useMutation({
    // ADR-010: PATCH answers the full CartResponse — that body IS the new
    // view, so it goes straight into the cache; no refetch of a fresh fact.
    mutationFn: ({
      bookId,
      quantity,
    }: QuantityChange): Promise<CartResponse> =>
      // The zod mirror (FE-02) keeps the wire shape honest; the 1..stock
      // verdict above it is the service's (FR-12, LC-16).
      updateItemQuantity(
        bookId,
        updateQuantityRequestSchema.parse({ quantity }),
      ),
    onSuccess: (cart) => {
      queryClient.setQueryData<CartResponse>(CART_QUERY_KEY, cart)
      onWriteSuccess()
    },
    onError: (error, change) => {
      onWriteError(error, change)
      refreshAfterStaleWrite(error)
    },
  })

  const removeLine = useMutation({
    mutationFn: ({ bookId }: QuantityChange): Promise<void> =>
      removeItem(bookId),
    onSuccess: () => {
      // 204 carries no body: the refetch IS the fresh view (FR-13's totals
      // recalculate server-side, never by client subtraction — D-08).
      invalidateCart(queryClient)
      onWriteSuccess()
    },
    onError: (error, change) => {
      onWriteError(error, change)
      refreshAfterStaleWrite(error)
    },
  })

  return { changeQuantity, removeLine }
}

/** Announce a committed cart write to every mounted cart read — the seam
 *  AddToCartButton uses after a successful POST (FR-10 → FR-11 freshness). */
export function invalidateCart(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: CART_QUERY_KEY })
}
