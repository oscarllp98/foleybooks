import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'

describe('EmptyState', () => {
  it('render_showsTitleAndMessageAsLandmark', () => {
    render(
      <EmptyState title="No results" message="Try a different search term." />,
    )

    expect(
      screen.getByRole('region', { name: 'No results' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 2, name: 'No results' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Try a different search term.')).toBeInTheDocument()
  })

  it('cta_whenActionProvided_isKeyboardUsable', async () => {
    // FR-11 empty cart → "browse" call to action (NFR-04 keyboard path).
    const user = userEvent.setup()
    const onBrowse = vi.fn()
    render(
      <EmptyState title="Your cart is empty">
        <button type="button" onClick={onBrowse}>
          Browse books
        </button>
      </EmptyState>,
    )

    await user.tab()
    await user.keyboard('{Enter}')

    expect(onBrowse).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Browse books' })).toHaveFocus()
  })
})

describe('ErrorState', () => {
  it('render_alertsWithDefaultGenericMessage', () => {
    render(<ErrorState />)

    const alert = screen.getByRole('alert')
    expect(
      screen.getByRole('heading', { level: 2, name: 'Something went wrong' }),
    ).toBeInTheDocument()
    expect(alert).toHaveTextContent(
      'We could not complete this request. Please try again.',
    )
  })

  it('render_whenTraceIdGiven_showsItForSupportTraceability', () => {
    // NFR-06: users see a generic error plus a traceable identifier.
    render(<ErrorState traceId="a1b2c3d4" />)

    expect(screen.getByText('Reference: a1b2c3d4')).toBeInTheDocument()
  })

  it('onRetry_whenRetryClicked_firesCallback', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(<ErrorState onRetry={onRetry} />)

    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(onRetry).toHaveBeenCalled()
  })

  it('render_withoutRetryHandler_hidesRetryButton', () => {
    render(<ErrorState title="Not found" />)

    expect(screen.queryByRole('button')).toBeNull()
  })

  it('render_whenCustomTitlesAndLabelsGiven_usesThem', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(
      <ErrorState
        title="Catalog unavailable"
        message="The book list could not be loaded."
        retryLabel="Reload catalog"
        onRetry={onRetry}
      />,
    )

    expect(
      screen.getByRole('heading', { level: 2, name: 'Catalog unavailable' }),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Reload catalog' }))

    expect(onRetry).toHaveBeenCalled()
  })
})
