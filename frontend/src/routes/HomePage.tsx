// FE-10: the "/" landing page, carried over verbatim from the P-05 App
// placeholder. FE-11 replaces the body with the paginated BookList once the
// catalog queries land (TanStack Query); the hero copy stays until then.

export function HomePage() {
  return (
    <section className="flex flex-col items-center gap-2 py-16 text-center">
      <h1 className="text-3xl font-bold tracking-tight">Foley Books</h1>
      <p className="text-neutral-600">
        Your next favourite story is a search away.
      </p>
    </section>
  )
}
