import { BookList } from '../features/catalog/BookList'

// FE-11: the "/" landing is the browsable catalog now (FR-06). The page is a
// plain mount point — BookList owns the fetch and the browse state, and the
// hero copy FE-10 carried has served its placeholder purpose.

export function HomePage() {
  return <BookList />
}
