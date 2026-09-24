package com.foleybooks.catalog.book.service;

import com.foleybooks.catalog.book.api.BookResponse;
import com.foleybooks.catalog.book.api.BookSort;
import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.book.mapping.BookMapper;
import com.foleybooks.catalog.book.repository.BookRepository;
import com.foleybooks.catalog.book.repository.BookSpecifications;
import com.foleybooks.catalog.common.PageEnvelope;
import com.foleybooks.catalog.common.PageMeta;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The FR-06 browse rule (CA-06, D-07) and the CA-08 filter's wiring (FR-08,
 * FR-09), owned here exactly where C8 wants it — never in the controller, never
 * in the repository. Three numbers define the paging: default size 20, max size
 * 100, default sort title ascending. Which page a request actually gets is the
 * clamping of LC-11, decided <em>after</em> the first fetch: the repository's own
 * {@code Page} metadata says how many pages exist, so a page past the last one is
 * re-read as the last page (or as page 0 of an empty catalog) instead of serving a
 * fabricated empty window — the client's {@code page.number} always names the page
 * it received.
 *
 * <p>The CA-08 {@code search}/{@code categoryId} filter is composed once, up
 * front, into a single {@link Specification} ({@link BookSpecifications#matching}
 * owns every query-execution rule behind that call — case-insensitive matching,
 * metacharacter escaping, {@code AND} composition; trimming and the blank-means-
 * no-filter decision already happened at the boundary, per C23) and reused
 * verbatim on both the first fetch and the past-the-end re-fetch, so a clamp can
 * never silently drop the filter the client asked for any more than it can drop
 * the sort (see
 * {@code listBooks_whenPastLastPageIsRefetched_preservesTheRequestedSort}).
 *
 * <p>Malformed non-numeric parameters never reach this class: the MVC binder
 * rejects them with a 400 at the boundary (LC-28, D-15) — the same is true of a
 * malformed {@code categoryId} or an over-long {@code search}, neither ever a
 * "no matches" result but a boundary rejection before this method is called.
 * The projection goes
 * through {@link BookMapper#toResponseList} (ADR-009: the mapper owns the
 * shape at every cardinality) inside the read-only transaction, so the lazy
 * category is still resolvable ({@code open-in-view} is off), and the
 * {@link PageMeta} is assembled here — the wire records in {@code common}
 * never learn Spring Data exists (C7). No entity escapes (C9).
 *
 * <p>{@link #getBook(UUID)} is CA-09's detail read on the same collaborators:
 * one {@code findById}, the same mapper, and a {@link BookNotFoundException}
 * when the id resolves to no row — the service's own 404, never a controller
 * null-check (C8). {@link #getBooks(java.util.Collection)} is CA-11's batch read
 * (D-10) beside it: one {@code findAllById}, the same mapper, and the opposite
 * not-found rule — an unknown id is absent from the array, the LC-14 signal the
 * cart enrichment is built to read, never a 404 that would fail the whole batch.
 */
@Service
public class BookServiceImpl implements BookService {

    /**
     * FR-06's default sort, applied when the client asks for none (the omitted or
     * blank {@code sort} parameter). CA-07's whitelist lives in the boundary
     * ({@code BookSortConverter}), so every {@link BookSort} that reaches this
     * class is already one the query can resolve; the default stays title
     * ascending.
     */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "title");

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;

    public BookServiceImpl(BookRepository bookRepository, BookMapper bookMapper) {
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageEnvelope<BookResponse> listBooks(int page, int size, BookSort sort, String search, UUID categoryId) {
        Sort effectiveSort = toSpringSort(sort);
        int effectiveSize = clampSize(size);
        int requestedPage = Math.max(page, 0);
        Specification<Book> filter = BookSpecifications.matching(search, categoryId);
        Page<Book> books = bookRepository.findAll(filter, PageRequest.of(requestedPage, effectiveSize, effectiveSort));
        if (requestedPage > 0 && !books.hasContent()) {
            books = bookRepository.findAll(filter, PageRequest.of(lastPageIndex(books), effectiveSize, effectiveSort));
        }
        return new PageEnvelope<>(bookMapper.toResponseList(books.getContent()), pageMeta(books));
    }

    /**
     * FR-07's detail read (CA-09). One {@code findById} and one projection
     * through the same {@link BookMapper#toResponse} the list uses (ADR-009), so
     * the badge a card shows and the badge the detail page shows are computed by
     * one owner from one stored quantity (D-09). The mapping happens
     * <em>inside</em> this read-only transaction on purpose: {@code category} is
     * a lazy proxy and {@code open-in-view} is off (application.yml), so a
     * projection deferred to the controller would surface as a
     * {@code LazyInitializationException} — a 500 for a book that exists.
     */
    @Override
    @Transactional(readOnly = true)
    public BookResponse getBook(UUID id) {
        return bookRepository.findById(id)
                .map(bookMapper::toResponse)
                .orElseThrow(() -> BookNotFoundException.forId(id));
    }

    /**
     * CA-11's batch read (D-10, FR-11 enrichment). One {@code findAllById} — a
     * single {@code WHERE id IN (...)} round-trip, which is the whole point of
     * D-10: the cart's enrichment must be one east-west call, not one {@code
     * findById} per line. The projection goes through the same {@link
     * BookMapper#toResponseList} the list uses (ADR-009), <em>inside</em> this
     * read-only transaction because {@code category} is lazy and {@code
     * open-in-view} is off — the identical reason {@link #getBook(UUID)} maps in
     * transaction, and the reason a batch line can never drift from a detail line.
     *
     * <p>Unknown ids simply do not appear (the {@code IN} query returns only the
     * rows that exist); that absent-line is the LC-14 signal the caller reads,
     * never a thrown 404 (see {@link BookService#getBooks}). A {@code null} or
     * empty request short-circuits before the repository: {@code findAllById} over
     * an empty collection is not a query Spring Data guarantees. Both empty shapes
     * are HTTP-reachable — an absent {@code ?ids=} binds to {@code null} and a
     * blank one to an empty list — so FR-11's empty cart answers {@code []} without
     * touching the database either way; a non-blank malformed id never gets here
     * (it is a boundary 400). No sort is applied because none is contractual for a
     * keyed batch lookup — the caller maps the result by {@code id}.
     */
    @Override
    @Transactional(readOnly = true)
    public List<BookResponse> getBooks(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return bookMapper.toResponseList(bookRepository.findAllById(ids));
    }

    /**
     * The single seam where a {@link BookSort} becomes the Spring Data {@code Sort}
     * the repository consumes; {@code null} — the sort parameter omitted or left
     * blank — answers with the FR-06 default of title ascending.
     */
    private static Sort toSpringSort(BookSort sort) {
        if (sort == null) {
            return DEFAULT_SORT;
        }
        Sort.Direction direction = switch (sort.direction()) {
            case ASC -> Sort.Direction.ASC;
            case DESC -> Sort.Direction.DESC;
        };
        return Sort.by(direction, sort.field().property());
    }

    /** LC-11: {@code size < 1 → 20}, {@code size > 100 → 100}; everything else is honored. */
    private static int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /**
     * Last page of whatever the repository just described. An empty catalog has
     * no pages at all, so its "last page" is page 0 — that is what keeps the
     * clamp of a past-the-end request from returning a phantom window.
     */
    private static int lastPageIndex(Page<Book> books) {
        return Math.max(books.getTotalPages() - 1, 0);
    }

    private static PageMeta pageMeta(Page<Book> books) {
        return new PageMeta(books.getTotalElements(), books.getTotalPages(), books.getNumber(), books.getSize());
    }
}
