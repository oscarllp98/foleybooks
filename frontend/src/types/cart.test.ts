import { describe, expect, it } from 'vitest'
import { addItemRequestSchema, updateQuantityRequestSchema } from './cart'

const BOOK_ID = '3fa85f64-5717-4562-b3fc-2c963f66afa6'

describe('addItemRequestSchema (FR-10/LC-12 mirror)', () => {
  it('addItem_whenBookIdMissingOrMalformed_rejects', () => {
    expect(addItemRequestSchema.safeParse({ quantity: 1 }).success).toBe(false)
    expect(
      addItemRequestSchema.safeParse({ bookId: 'not-a-uuid', quantity: 1 })
        .success,
    ).toBe(false)
  })

  it('addItem_whenQuantityBelow1_rejectsAsTooSmall', () => {
    expect(
      addItemRequestSchema.safeParse({ bookId: BOOK_ID, quantity: 0 }).success,
    ).toBe(false)
    expect(
      addItemRequestSchema.safeParse({ bookId: BOOK_ID, quantity: -3 }).success,
    ).toBe(false)
  })

  it('addItem_whenQuantityIsHighButWellFormed_acceptsBecauseStockIsTheServerGate', () => {
    expect(
      addItemRequestSchema.safeParse({ bookId: BOOK_ID, quantity: 9999 })
        .success,
    ).toBe(true)
  })

  it('addItem_whenQuantityNotInteger_rejects', () => {
    expect(
      addItemRequestSchema.safeParse({ bookId: BOOK_ID, quantity: 2.5 })
        .success,
    ).toBe(false)
  })
})

describe('updateQuantityRequestSchema (FR-12 mirror)', () => {
  it('updateQuantity_whenZero_acceptsBecauseZeroRemovesTheLine', () => {
    expect(updateQuantityRequestSchema.safeParse({ quantity: 0 }).success).toBe(
      true,
    )
  })

  it('updateQuantity_whenNegative_rejectsAsNonNegative', () => {
    const result = updateQuantityRequestSchema.safeParse({ quantity: -1 })

    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.message).toBe('must not be negative')
  })
})
