import { describe, expect, it } from 'vitest'
import { formatEur } from './money'

describe('formatEur', () => {
  it('formatsIntegersAndDecimalsAsExactEURDisplay', () => {
    // D-08: rendering only — no arithmetic, the server already summed.
    expect(formatEur(31.99)).toBe('€31.99')
    expect(formatEur(0)).toBe('€0.00')
    expect(formatEur(1234.5)).toBe('€1,234.50')
  })

  it('matchesIntlContractForCatalogPrices', () => {
    const reference = new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'EUR',
    })

    expect(formatEur(63.98)).toBe(reference.format(63.98))
  })
})
