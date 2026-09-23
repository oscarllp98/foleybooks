package com.foleybooks.catalog.book.repository;

import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.category.domain.Category;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

/**
 * The CA-08 browse filter (FR-08, FR-09, D-06): free-text {@code search} over title
 * and author, optionally composed with an exact {@code categoryId}. Both criteria
 * are built here and nowhere else — D-06's "raw SQL lives in the repository layer
 * only" generalises to "predicate-building lives in the repository layer": the
 * controller and service only ever forward the two request values, so the
 * matching and escaping rules below have exactly one owner. Trimming and the
 * blank-means-no-filter decision are <em>not</em> owned here — like every other
 * piece of caller-supplied text, they are normalized at the boundary
 * ({@link com.foleybooks.catalog.book.api.BookController}, per C23), so this
 * factory's precondition is a {@code null} or already-trimmed, non-blank term.
 *
 * <p>{@code search} is matched with PostgreSQL's {@code ILIKE '%term%'} — D-06
 * verbatim, and ADR-003's "no search index" note reasons about exactly this
 * operator (a case-insensitive match no B-tree on {@code title}/{@code author}
 * could serve; the {@code LOWER(col) LIKE LOWER(...)} workaround Hibernate's
 * plain {@code CriteriaBuilder.like} would have produced is avoided here on
 * purpose: since H2 is forbidden everywhere and PostgreSQL is the only database
 * this project ever runs against (AGENTS.md §7), the portable-but-slower form
 * buys nothing and real {@code ILIKE} is what the decision actually names). The
 * term's LIKE metacharacters ({@code \}, {@code %}, {@code _}) are escaped
 * before wrapping in wildcards — that is LC-10's "special characters ... literal
 * handling": a search for {@code 100%} looks for a title containing "100%", and
 * a search for a bare {@code %} matches nothing instead of degenerating into a
 * match-everything wildcard. Nothing is concatenated into SQL either way: the
 * Criteria API binds the pattern as a query parameter, so SQL-shaped noise (the
 * LC-28 injection strings {@code BookSortConverter} already rejects for
 * {@code sort}) is simply a search that finds no book (D-06 "parameterized",
 * LC-10 "never an error page").
 *
 * <p>Each criterion is optional and they compose with {@code AND}: a {@code null}
 * {@code search} (the boundary's answer to a blank or omitted term) adds no text
 * predicate, and a {@code null} {@code categoryId} adds none either, so
 * {@link #matching} with both empty is the whole catalog. Both present means
 * search <em>within</em> a category (FR-08's "combinable with category filter").
 * The category predicate is an exact id comparison against {@code Book.category}'s
 * identifier; Hibernate 6 resolves that path to the {@code category_id} FK column
 * itself (V1's {@code ix_books_category} index) with no join to
 * {@code categories} — verified, not assumed, by a generated-SQL check against
 * real PostgreSQL (see {@code BookRepositoryTest}'s category-filter cases) — and
 * an id matching no category at all is an empty page, never an error (FR-09,
 * LC-31).
 */
public final class BookSpecifications {

    private static final char LIKE_ESCAPE_CHAR = '\\';

    private BookSpecifications() {
        // Static factory only.
    }

    /**
     * The composed filter for one browse request. {@code search} must already be
     * trimmed and non-blank when present (that is the boundary's job, per C23 —
     * see {@link com.foleybooks.catalog.book.api.BookController}); {@code null}
     * means "no text filter". {@code categoryId} is "no category filter" when
     * {@code null}.
     */
    public static Specification<Book> matching(String search, UUID categoryId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search != null) {
                String pattern = likePattern(search);
                // Hibernate's own CriteriaBuilder carries ILIKE; the plain JPA one
                // does not (it has no equivalent of "case-insensitive like" at all).
                // The CriteriaBuilder a Hibernate session hands back is always
                // instance-of this interface — the standard way in to its extensions.
                // H2 is forbidden everywhere (AGENTS.md §7), so PostgreSQL's ILIKE
                // is always available underneath.
                HibernateCriteriaBuilder hibernateCb = (HibernateCriteriaBuilder) cb;
                predicates.add(cb.or(
                        hibernateCb.ilike(root.<String>get("title"), pattern, LIKE_ESCAPE_CHAR),
                        hibernateCb.ilike(root.<String>get("author"), pattern, LIKE_ESCAPE_CHAR)));
            }
            if (categoryId != null) {
                Path<Category> category = root.<Category>get("category");
                predicates.add(cb.equal(category.<UUID>get("id"), categoryId));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String likePattern(String search) {
        String escaped = search
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
