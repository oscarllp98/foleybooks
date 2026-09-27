import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Spinner } from './Spinner'

describe('Spinner', () => {
  it('render_announcesLoadingThroughPoliteStatusRegion', () => {
    render(<Spinner />)

    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('Loading')
    expect(status).toHaveAttribute('aria-live', 'polite')
  })

  it('render_whenCustomLabelGiven_announcesIt', () => {
    render(<Spinner label="Adding to cart" />)

    expect(screen.getByRole('status')).toHaveTextContent('Adding to cart')
  })

  it('render_whenStaleContentPassed_keepsItLaidOutButFlaggedOutOfTheAccessibilityTree', () => {
    const { container } = render(
      <Spinner>
        <p>stale list</p>
      </Spinner>,
    )

    // Loading announcement (NFR-03) and the previous page stays visible in
    // place while refetching — no removal, no layout jump (jsdom renders no
    // pixels, so DOM presence is the available "visible" truth).
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(within(container).getByText('stale list')).toBeInTheDocument()
    // The aria-hidden contract that excludes the stale subtree from the
    // accessibility tree is asserted here; browsers/AT consume it, and
    // Testing Library's DOM-level queries deliberately ignore aria-hidden.
    expect(
      within(container).getByText('stale list').parentElement,
    ).toHaveAttribute('aria-hidden', 'true')
  })
})
