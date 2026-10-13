import { http } from '../lib/http'
import type {
  AddItemRequest,
  CartResponse,
  UpdateQuantityRequest,
} from '../types/cart'

// FE-03: the only place order-service HTTP happens (C10). These calls run under
// the shared instance's auth flow (they are not /auth/ paths, so a 401 triggers
// refresh-then-retry, LC-07/LC-22) and the caller's identity rides the token's
// sub claim — no user id is ever a parameter here (C26). Business verdicts
// (stock gates, flags, totals) belong to the service; every 422/404/503
// ProblemDetail simply rejects the promise for the calling hook (FE-14/FE-15)
// to interpret via its status and extra properties (availableStock, etc.).

const CART_PATH = '/cart'
const ITEMS_PATH = `${CART_PATH}/items`

export async function getCart(): Promise<CartResponse> {
  const { data } = await http.get<CartResponse>(CART_PATH)
  return data
}

export async function addItem(request: AddItemRequest): Promise<CartResponse> {
  const { data } = await http.post<CartResponse>(ITEMS_PATH, request)
  return data
}

export async function updateItemQuantity(
  bookId: string,
  request: UpdateQuantityRequest,
): Promise<CartResponse> {
  const { data } = await http.patch<CartResponse>(
    `${ITEMS_PATH}/${bookId}`,
    request,
  )
  return data
}

export async function removeItem(bookId: string): Promise<void> {
  await http.delete<void>(`${ITEMS_PATH}/${bookId}`)
}
