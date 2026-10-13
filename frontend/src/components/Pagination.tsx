interface PaginationProps {
  /** Current page, 0-based — the API's `page.number` (AGENTS.md §6). */
  page: number
  totalPages: number
  /** Page envelopes always carry it (AGENTS.md §6), so it is not optional. */
  totalElements: number
  onPageChange: (page: number) => void
  /** Rendered label of the first/last page button; defaults to Previous/Next. */
  previousLabel?: string
  nextLabel?: string
}

type PageItem = number | 'gap-before' | 'gap-after'

/**
 * First/last plus a ±1 window around the current page; the jump to either is
 * marked with a gap so intermediate buttons are never rendered (NFR-02 lists
 * stay cheap, and no page is hidden without a visible ellipsis).
 */
function buildPageItems(current: number, totalPages: number): PageItem[] {
  const wanted = new Set<number>([0, totalPages - 1])
  for (const candidate of [current - 1, current, current + 1]) {
    if (candidate >= 0 && candidate < totalPages) wanted.add(candidate)
  }
  const pages = [...wanted].sort((a, b) => a - b)

  const items: PageItem[] = []
  pages.forEach((pageNumber, index) => {
    const previous = pages[index - 1]
    if (previous !== undefined && pageNumber - previous > 1) {
      items.push(index === 0 ? 'gap-before' : 'gap-after')
    }
    items.push(pageNumber)
  })
  return items
}

/**
 * FE-05 (FR-06 pagination): 0-based nav shared by BookList and category
 * browsing. A single-page (or empty) result renders nothing — the empty
 * state carries that case (LC-31), and there is no point disabling a
 * control nobody can use.
 */
export function Pagination({
  page,
  totalPages,
  totalElements,
  onPageChange,
  previousLabel = 'Previous page',
  nextLabel = 'Next page',
}: PaginationProps) {
  if (totalPages <= 1) return null

  const isLast = page >= totalPages - 1

  return (
    <nav aria-label="Pagination" className="flex flex-wrap items-center gap-2">
      <button
        type="button"
        aria-label={previousLabel}
        disabled={page === 0}
        onClick={() => onPageChange(page - 1)}
        className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm text-neutral-700 enabled:hover:bg-neutral-100 disabled:cursor-not-allowed disabled:opacity-50"
      >
        ‹
      </button>
      {buildPageItems(page, totalPages).map((item, index) =>
        typeof item === 'number' ? (
          <button
            key={item}
            type="button"
            aria-label={`Go to page ${item + 1}`}
            aria-current={item === page ? 'page' : undefined}
            onClick={() => onPageChange(item)}
            className="min-w-9 rounded-md border border-neutral-300 px-3 py-1.5 text-sm text-neutral-700 enabled:hover:bg-neutral-100 aria-[current=page]:border-neutral-800 aria-[current=page]:bg-neutral-800 aria-[current=page]:text-white"
          >
            {item + 1}
          </button>
        ) : (
          <span
            key={`${item}-${index}`}
            aria-hidden="true"
            className="px-1 text-sm text-neutral-500"
          >
            …
          </span>
        ),
      )}
      <button
        type="button"
        aria-label={nextLabel}
        disabled={isLast}
        onClick={() => onPageChange(page + 1)}
        className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm text-neutral-700 enabled:hover:bg-neutral-100 disabled:cursor-not-allowed disabled:opacity-50"
      >
        ›
      </button>
      <p className="ml-auto text-sm text-neutral-600">
        Page {page + 1} of {totalPages} — {totalElements} results
      </p>
    </nav>
  )
}
