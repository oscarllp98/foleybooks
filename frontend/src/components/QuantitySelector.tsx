import { useState } from 'react'

interface QuantitySelectorProps {
  value: number
  onChange: (quantity: number) => void
  min?: number
  /** Live stock ceiling (FR-10/LC-12); omitted when stock is unknown yet. */
  max?: number
  label?: string
}

function clamp(raw: number, min: number, max: number): number {
  if (Number.isNaN(raw)) return min
  return Math.min(Math.max(raw, min), max)
}

/**
 * FE-05 (FR-10, FR-12, LC-16): numeric quantity stepper shared by
 * AddToCartButton and CartPage. While typing, a local draft lets temporary
 * invalid input ("", "12" mid-keystroke) exist; the committed value is always
 * an integer inside [min, max] — typed out-of-range values clamp on blur, so
 * no invalid number ever reaches onChange. Server-side stock remains the
 * authoritative 422 gate (D-08: the client only bounds the widget). The draft
 * only overrides the prop while it is ahead of it; the moment the prop moves
 * (server response, parent decline, stepper) the fresh value renders — no
 * synchronization effect, no ref reads in render.
 */
export function QuantitySelector({
  value,
  onChange,
  min = 1,
  max = 999,
  label = 'Quantity',
}: QuantitySelectorProps) {
  const [editing, setEditing] = useState(false)
  const [touched, setTouched] = useState(false)
  const [draft, setDraft] = useState(String(value))
  const display =
    editing || (touched && draft !== String(value)) ? draft : String(value)
  const current = clamp(Number.parseInt(display, 10), min, max)

  const commit = (raw: number): void => {
    const next = clamp(raw, min, max)
    setEditing(false)
    setDraft(String(next))
    if (next !== value) onChange(next)
  }

  return (
    <div className="inline-flex items-center gap-1">
      <button
        type="button"
        aria-label={`Decrease ${label.toLowerCase()}`}
        disabled={current <= min}
        onClick={() => commit(current - 1)}
        className="rounded-md border border-neutral-300 px-2.5 py-1 text-neutral-700 enabled:hover:bg-neutral-100 disabled:cursor-not-allowed disabled:opacity-50"
      >
        −
      </button>
      <input
        type="number"
        aria-label={label}
        inputMode="numeric"
        min={min}
        max={max}
        value={display}
        onChange={(event) => {
          setDraft(event.target.value)
          setEditing(true)
          setTouched(true)
          const parsed = Number.parseInt(event.target.value, 10)
          if (parsed >= min && parsed <= max) onChange(parsed)
        }}
        onBlur={() => {
          if (editing) commit(Number.parseInt(draft, 10))
        }}
        className="w-14 rounded-md border border-neutral-300 px-2 py-1 text-center text-neutral-800 [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
      />
      <button
        type="button"
        aria-label={`Increase ${label.toLowerCase()}`}
        disabled={current >= max}
        onClick={() => commit(current + 1)}
        className="rounded-md border border-neutral-300 px-2.5 py-1 text-neutral-700 enabled:hover:bg-neutral-100 disabled:cursor-not-allowed disabled:opacity-50"
      >
        +
      </button>
    </div>
  )
}
