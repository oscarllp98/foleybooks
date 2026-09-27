import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { QuantitySelector } from './QuantitySelector'

describe('QuantitySelector', () => {
  it('increment_whenBelowMax_raisesValueByOne', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<QuantitySelector value={2} max={10} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Increase quantity' }))

    expect(onChange).toHaveBeenCalledWith(3)
  })

  it('increment_whenAtMax_buttonIsDisabled', () => {
    render(<QuantitySelector value={5} max={5} onChange={vi.fn()} />)

    expect(
      screen.getByRole('button', { name: 'Increase quantity' }),
    ).toBeDisabled()
  })

  it('decrement_whenAtMin_buttonIsDisabled', () => {
    render(<QuantitySelector value={1} min={1} onChange={vi.fn()} />)

    expect(
      screen.getByRole('button', { name: 'Decrease quantity' }),
    ).toBeDisabled()
  })

  it('decrement_whenAboveMin_lowersValueByOne', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<QuantitySelector value={4} max={10} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Decrease quantity' }))

    expect(onChange).toHaveBeenCalledWith(3)
  })

  it('onChange_whenValidQuantityTyped_commitsImmediately', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<QuantitySelector value={1} max={10} onChange={onChange} />)

    await user.clear(screen.getByRole('spinbutton', { name: 'Quantity' }))
    await user.type(screen.getByRole('spinbutton', { name: 'Quantity' }), '7')

    expect(onChange).toHaveBeenLastCalledWith(7)
  })

  it('commit_whenOutOfRangeValueBlurs_clampsToMaxWithoutEmittingInvalidValue', () => {
    // LC-16: extreme input never reaches the caller as-is.
    const onChange = vi.fn()
    render(<QuantitySelector value={2} max={5} onChange={onChange} />)
    const input = screen.getByRole('spinbutton', { name: 'Quantity' })

    act(() => {
      fireEvent.change(input, { target: { value: '99999999999' } })
    })
    expect(input).toHaveValue(99999999999)
    fireEvent.blur(input)

    expect(onChange).toHaveBeenCalledWith(5)
    expect(input).toHaveValue(5)
  })

  it('commit_whenClearedOrNegativeBlurs_clampsToMin', () => {
    const onChange = vi.fn()
    render(<QuantitySelector value={3} min={1} max={10} onChange={onChange} />)
    const input = screen.getByRole('spinbutton', { name: 'Quantity' })

    act(() => {
      fireEvent.change(input, { target: { value: '-4' } })
    })
    fireEvent.blur(input)

    expect(onChange).toHaveBeenCalledWith(1)
    expect(input).toHaveValue(1)
  })

  it('commit_whenCartRemovalAllowed_clampsToZeroMin', () => {
    // FR-12: quantity 0 is legal in the cart and means "remove the line".
    const onChange = vi.fn()
    render(<QuantitySelector value={2} min={0} max={10} onChange={onChange} />)
    const input = screen.getByRole('spinbutton', { name: 'Quantity' })

    fireEvent.change(input, { target: { value: '0' } })
    fireEvent.blur(input)

    expect(onChange).toHaveBeenCalledWith(0)
  })

  it('render_whenControlledValueChangesFromOutside_showsIt', () => {
    const { rerender } = render(
      <QuantitySelector value={2} max={10} onChange={vi.fn()} />,
    )

    rerender(<QuantitySelector value={6} max={10} onChange={vi.fn()} />)

    expect(screen.getByRole('spinbutton', { name: 'Quantity' })).toHaveValue(6)
  })

  it('render_whenCustomLabelGiven_usesItForAccessibleNames', () => {
    render(
      <QuantitySelector
        value={1}
        max={10}
        onChange={vi.fn()}
        label="cart quantity"
      />,
    )

    expect(
      screen.getByRole('spinbutton', { name: 'cart quantity' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Decrease cart quantity' }),
    ).toBeInTheDocument()
  })
})
