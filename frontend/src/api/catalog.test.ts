import { beforeEach, describe, expect, it } from 'vitest'
import * as catalogApi from './catalog'
import { clearTokens } from '../lib/tokens'
import type { RecordedRequest } from '../test/recordingAdapter'
import { installRecordingAdapter } from '../test/recordingAdapter'

const BOOK_PAGE = {
  content: [
    {
      id: '00000000-0000-0000-0000-00000000cb06',
      title: 'Clean Code',
      author: 'Robert C. Martin',
      isbn: '9780132350884',
      price: 31.99,
      coverUrl: 'https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg',
      availability: 'IN_STOCK',
      stockQuantity: 12,
      category: { id: 'cat-1', name: 'Technology' },
    },
  ],
  page: { totalElements: 1, totalPages: 1, number: 0, size: 20 },
} as const

const CATEGORY_PAGE = {
  content: [{ id: 'cat-1', name: 'Technology' }],
  page: { totalElements: 1, totalPages: 1, number: 0, size: 20 },
} as const

describe('api/catalog', () => {
  let requests: RecordedRequest[]

  beforeEach(() => {
    clearTokens()
    requests = installRecordingAdapter(() => [200, BOOK_PAGE])
  })

  it('getBooks_sendsNoParamsWhenQueryOmitted_returnsPageEnvelope', async () => {
    const result = await catalogApi.getBooks()

    expect(requests[0]).toMatchObject({ method: 'GET', url: '/books' })
    expect(requests[0].params).toEqual({})
    expect(result).toEqual(BOOK_PAGE)
  })

  it('getBooks_forwardsBrowseQueryUnchanged', async () => {
    await catalogApi.getBooks({
      page: 2,
      size: 40,
      sort: 'price,desc',
      search: 'clean',
      categoryId: 'cat-1',
    })

    expect(requests[0].params).toEqual({
      page: 2,
      size: 40,
      sort: 'price,desc',
      search: 'clean',
      categoryId: 'cat-1',
    })
  })

  it('getBook_addressesResourceByIdOnPath', async () => {
    const book = BOOK_PAGE.content[0]
    requests = installRecordingAdapter(() => [200, book])

    const result = await catalogApi.getBook(
      '00000000-0000-0000-0000-00000000cb06',
    )

    expect(requests[0]).toMatchObject({
      method: 'GET',
      url: '/books/00000000-0000-0000-0000-00000000cb06',
    })
    expect(result).toEqual(book)
  })

  it('getCategories_forwardsPagingQuery_returnsCategoryPage', async () => {
    requests = installRecordingAdapter(() => [200, CATEGORY_PAGE])

    const result = await catalogApi.getCategories({ page: 1, size: 100 })

    expect(requests[0]).toMatchObject({ method: 'GET', url: '/categories' })
    expect(requests[0].params).toEqual({ page: 1, size: 100 })
    expect(result).toEqual(CATEGORY_PAGE)
  })
})
