import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Pagination } from './Pagination'

const noop = () => undefined

function renderPagination(
  overrides: Partial<ComponentProps<typeof Pagination>> = {},
) {
  const onPageChange = vi.fn()
  render(
    <Pagination
      page={0}
      totalPages={3}
      totalElements={60}
      onPageChange={onPageChange}
      {...overrides}
    />,
  )
  return { onPageChange }
}

describe('Pagination', () => {
  it('render_whenSinglePage_rendersNothing', () => {
    const { container } = render(
      <Pagination
        page={0}
        totalPages={1}
        totalElements={20}
        onPageChange={noop}
      />,
    )

    expect(container).toBeEmptyDOMElement()
  })

  it('render_whenMultiplePages_showsOneButtonPerPageAndCurrentMarker', () => {
    renderPagination({ page: 1, totalPages: 3 })

    expect(
      screen.getByRole('navigation', { name: 'Pagination' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Go to page 1' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Go to page 2' }),
    ).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('Page 2 of 3 — 60 results')).toBeInTheDocument()
  })

  it('previousButton_whenOnFirstPage_isDisabled', () => {
    renderPagination({ page: 0 })

    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
  })

  it('nextButton_whenOnLastPage_isDisabled', () => {
    renderPagination({ page: 2, totalPages: 3 })

    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
  })

  it('onPageChange_whenNextClicked_movesForwardByOne', async () => {
    const user = userEvent.setup()
    const { onPageChange } = renderPagination({ page: 0, totalPages: 3 })

    await user.click(screen.getByRole('button', { name: 'Next page' }))

    expect(onPageChange).toHaveBeenCalledWith(1)
  })

  it('onPageChange_whenPageNumberClicked_jumpsToThatPage', async () => {
    const user = userEvent.setup()
    const { onPageChange } = renderPagination({ page: 0, totalPages: 3 })

    await user.click(screen.getByRole('button', { name: 'Go to page 3' }))

    expect(onPageChange).toHaveBeenCalledWith(2)
  })

  it('render_whenPagesSkippedInWindow_marksGapAndKeepsFirstAndLast', () => {
    renderPagination({ page: 4, totalPages: 10 })

    // current ±1 plus first/last: 1, 4, 5, 6, 10 — the rest collapsed.
    for (const page of [1, 4, 5, 6, 10]) {
      expect(
        screen.getByRole('button', { name: `Go to page ${page}` }),
      ).toBeInTheDocument()
    }
    for (const hidden of [2, 3, 7, 8, 9]) {
      expect(
        screen.queryByRole('button', { name: `Go to page ${hidden}` }),
      ).toBeNull()
    }
    expect(screen.getAllByText('…')).toHaveLength(2)
  })
})
