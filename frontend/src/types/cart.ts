import { z } from 'zod'

// FE-02: mirrors order-service's cart request DTO validation. Upper quantity
// bound is deliberately absent on add: live stock is the service's 422 gate
// (FR-10/LC-13), the client only enforces the static shape.

export const addItemRequestSchema = z.object({
  bookId: z.uuid({ error: 'is required' }),
  quantity: z
    .number({ error: 'is required' })
    .int()
    .min(1, 'must be at least 1'),
})

// PATCH quantity 0 is legal and means "remove the line" (FR-12).
export const updateQuantityRequestSchema = z.object({
  quantity: z
    .number({ error: 'is required' })
    .int()
    .min(0, 'must not be negative'),
})

export type AddItemRequest = z.infer<typeof addItemRequestSchema>
export type UpdateQuantityRequest = z.infer<typeof updateQuantityRequestSchema>

// CartResponse contract (plan §2, ADR-005): catalog-sourced fields are null
// on an unavailable line (LC-14) — the honest encoding of "no book to read".

export interface CartItemResponse {
  bookId: string
  title: string | null
  author: string | null
  coverUrl: string | null
  unitPrice: number | null
  quantity: number
  lineTotal: number | null
  stockQuantity: number | null
  available: boolean
  insufficientStock: boolean
}

export interface CartResponse {
  items: CartItemResponse[]
  total: number
  currency: 'EUR'
}
