// FE-02: mirrors catalog-service's response records (plan §2, ADR-009).
// Catalog reads are public and the frontend never writes to it in the MVP,
// so there are no request-validation schemas here; GET /books query handling
// (FR-06/LC-11 clamping, LC-28 rejection, BookSortConverter's whitelist)
// belongs to the browse controls in FE-11/FE-12.

export type Availability = 'OUT_OF_STOCK' | 'LOW_STOCK' | 'IN_STOCK'

// PageEnvelope<T> from catalog-service/common: the custom four-field page
// metadata, never Spring's default PageImpl JSON.
export interface Page<T> {
  content: T[]
  page: {
    totalElements: number
    totalPages: number
    number: number
    size: number
  }
}

export interface CategoryResponse {
  id: string
  name: string
}

export interface BookResponse {
  id: string
  title: string
  author: string
  isbn: string
  price: number
  coverUrl: string
  availability: Availability
  stockQuantity: number
  category: CategoryResponse
}
