import { http } from '../lib/http'
import type { BookResponse, CategoryResponse, Page } from '../types/catalog'

// FE-03: the only place catalog-service HTTP happens (C10). Public reads, so
// no token is required; the request interceptor still attaches one when a
// session exists but the endpoints never depend on it (C22). Boundary rules the
// server owns — LC-11 page/size clamping, LC-28 rejection of malformed values,
// the title/price sort whitelist (CA-07) and the categoryId/search filters
// (CA-08) — are mirrored here only as the optional query shape; validating or
// clamping is the server's, and the browse controls (FE-11/FE-12) decide what
// to send. A malformed value simply surfaces as the server's 400 ProblemDetail.
//
// GET /books/batch is deliberately NOT mirrored here: it exists for the
// order-service Feign client that enriches the cart server-side (CA-11, D-10),
// an east-west call that never traverses the gateway (AGENTS.md §4). The
// frontend consumes the already-enriched CartResponse, so a batch client would
// be an unused second caller (C1).

const BOOKS_PATH = '/books'
const CATEGORIES_PATH = '/categories'

// Sort grammar accepted by BookSortConverter: 'title' | 'price' with an
// optional ',asc'/',desc'. Omitted, and the FR-06 title-ascending default applies.
export type BookSortParam =
  'title' | 'title,asc' | 'title,desc' | 'price' | 'price,asc' | 'price,desc'

export interface BookQuery {
  page?: number
  size?: number
  sort?: BookSortParam
  search?: string
  categoryId?: string
}

export interface CategoryQuery {
  page?: number
  size?: number
}

export async function getBooks(
  query: BookQuery = {},
): Promise<Page<BookResponse>> {
  const { data } = await http.get<Page<BookResponse>>(BOOKS_PATH, {
    params: query,
  })
  return data
}

export async function getBook(id: string): Promise<BookResponse> {
  const { data } = await http.get<BookResponse>(`${BOOKS_PATH}/${id}`)
  return data
}

export async function getCategories(
  query: CategoryQuery = {},
): Promise<Page<CategoryResponse>> {
  const { data } = await http.get<Page<CategoryResponse>>(CATEGORIES_PATH, {
    params: query,
  })
  return data
}
