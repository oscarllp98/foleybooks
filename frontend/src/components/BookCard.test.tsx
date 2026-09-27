import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { BookCard } from './BookCard'
import type { BookResponse } from '../types/catalog'

const BOOK: BookResponse = {
  id: '3fa85f64-5717-4562-b3fc-2c963f66afa6',
  title: 'Clean Code',
  author: 'Robert C. Martin',
  isbn: '9780132350884',
  price: 31.99,
  coverUrl: 'https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg',
  availability: 'IN_STOCK',
  stockQuantity: 12,
  category: { id: 'c1d2e3f4-0000-0000-0000-000000000000', name: 'Technology' },
}

const eur = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'EUR',
}).format(BOOK.price)

describe('BookCard', () => {
  it('render_whenBookGiven_showsCoverTitleAuthorPriceAndAvailability', () => {
    render(<BookCard book={BOOK} onOpen={vi.fn()} />)

    expect(
      screen.getByRole('article', { name: 'Clean Code' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 3, name: 'Clean Code' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Robert C. Martin')).toBeInTheDocument()
    expect(screen.getByText(eur)).toBeInTheDocument()
    expect(screen.getByText('In stock')).toBeInTheDocument()
    expect(
      screen.getByRole('img', { name: 'Cover of Clean Code' }),
    ).toBeInTheDocument()
  })

  it('onOpen_whenViewDetailsClicked_receivesTheBook', async () => {
    const user = userEvent.setup()
    const onOpen = vi.fn()
    render(<BookCard book={BOOK} onOpen={onOpen} />)

    await user.click(screen.getByRole('button', { name: 'View details' }))

    expect(onOpen).toHaveBeenCalledWith(BOOK)
  })

  it('cover_whenImageFailsToLoad_showsPlaceholderKeepingTitleAsAccessibleText', async () => {
    const { container } = render(<BookCard book={BOOK} onOpen={vi.fn()} />)
    const img = container.querySelector('img') as HTMLImageElement

    img.dispatchEvent(new Event('error'))

    await vi.waitFor(() => {
      expect(container.querySelector('img')).toBeNull()
    })
    const placeholder = screen.getByRole('img', {
      name: 'Cover image unavailable for Clean Code',
    })
    expect(placeholder).toHaveTextContent('Clean Code')
    // LC-29: the rest of the card is unaffected.
    expect(screen.getByText('Robert C. Martin')).toBeInTheDocument()
  })

  it('availability_whenOutOfStock_rendersOutOfStockBadge', () => {
    render(
      <BookCard
        book={{ ...BOOK, availability: 'OUT_OF_STOCK', stockQuantity: 0 }}
        onOpen={vi.fn()}
      />,
    )

    expect(screen.getByText('Out of stock')).toBeInTheDocument()
  })

  it('availability_whenLowStock_rendersLowStockBadge', () => {
    render(
      <BookCard
        book={{ ...BOOK, availability: 'LOW_STOCK', stockQuantity: 3 }}
        onOpen={vi.fn()}
      />,
    )

    expect(screen.getByText('Low stock')).toBeInTheDocument()
  })
})
