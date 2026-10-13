// FE-05: EUR rendering helper. D-08/NFR-07 — the frontend only ever formats
// numbers the server already computed (all totals are server-side money math);
// this function never does arithmetic on money.

const eurFormatter = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'EUR',
})

export function formatEur(amount: number): string {
  return eurFormatter.format(amount)
}
