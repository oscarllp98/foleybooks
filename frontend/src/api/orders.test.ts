import { beforeEach, describe, expect, it } from 'vitest'
import * as ordersApi from './orders'
import { clearTokens } from '../lib/tokens'
import type { RecordedRequest } from '../test/recordingAdapter'
import { installRecordingAdapter } from '../test/recordingAdapter'

const BOOK_ID = '00000000-0000-0000-0000-00000000cb06'

const CART = {
  items: [
    {
      bookId: BOOK_ID,
      title: 'Clean Code',
      author: 'Robert C. Martin',
      coverUrl: 'https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg',
      unitPrice: 31.99,
      quantity: 2,
      lineTotal: 63.98,
      stockQuantity: 12,
      available: true,
      insufficientStock: false,
    },
  ],
  total: 63.98,
  currency: 'EUR',
} as const

describe('api/orders', () => {
  let requests: RecordedRequest[]

  beforeEach(() => {
    clearTokens()
    requests = installRecordingAdapter(() => [200, CART])
  })

  it('getCart_readsOwnCart_returnsEnrichedResponse', async () => {
    const result = await ordersApi.getCart()

    expect(requests[0]).toMatchObject({ method: 'GET', url: '/cart' })
    expect(result).toEqual(CART)
  })

  it('addItem_postsPayload_returnsResultingCart', async () => {
    requests = installRecordingAdapter(() => [201, CART])

    const result = await ordersApi.addItem({ bookId: BOOK_ID, quantity: 2 })

    expect(requests[0]).toMatchObject({
      method: 'POST',
      url: '/cart/items',
      body: { bookId: BOOK_ID, quantity: 2 },
    })
    expect(result).toEqual(CART)
  })

  it('updateItemQuantity_patchesLineByBookId_returnsResultingCart', async () => {
    const result = await ordersApi.updateItemQuantity(BOOK_ID, { quantity: 3 })

    expect(requests[0]).toMatchObject({
      method: 'PATCH',
      url: `/cart/items/${BOOK_ID}`,
      body: { quantity: 3 },
    })
    expect(result).toEqual(CART)
  })

  it('updateItemQuantity_whenZero_stillPatchesRemovalSemantics', async () => {
    await ordersApi.updateItemQuantity(BOOK_ID, { quantity: 0 })

    expect(requests[0]).toMatchObject({
      method: 'PATCH',
      url: `/cart/items/${BOOK_ID}`,
      body: { quantity: 0 },
    })
  })

  it('removeItem_deletesLineByBookId_resolvesVoid', async () => {
    requests = installRecordingAdapter(() => [204, undefined])

    const result = await ordersApi.removeItem(BOOK_ID)

    expect(requests[0]).toMatchObject({
      method: 'DELETE',
      url: `/cart/items/${BOOK_ID}`,
    })
    expect(result).toBeUndefined()
  })
})
