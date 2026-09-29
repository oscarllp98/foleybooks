// FE-10: the guarded "/cart" landing. This route existed to give the
// authentication guard (LC-27) a door to stand in front of; FE-15 owns the
// real cart body — server-fetched lines, totals, quantity and removal — and
// replaces this landing. No fetch happens here (C10: data arrives through
// the api/ layer via TanStack Query, once the cart hooks exist).

export function CartPage() {
  return (
    <section
      aria-labelledby="cart-heading"
      className="flex flex-col gap-2 py-4"
    >
      <h1
        id="cart-heading"
        className="text-2xl font-bold tracking-tight text-neutral-900"
      >
        Your cart
      </h1>
      <p className="text-sm text-neutral-600">
        Items you add will show up here.
      </p>
    </section>
  )
}
