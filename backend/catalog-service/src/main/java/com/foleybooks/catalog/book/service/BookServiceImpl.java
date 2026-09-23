package com.foleybooks.catalog.book.service;

import com.foleybooks.catalog.book.api.BookResponse;
import com.foleybooks.catalog.book.api.BookSort;
import com.foleybooks.catalog.book.domain.Book;
import com.foleybooks.catalog.book.mapping.BookMapper;
import com.foleybooks.catalog.book.repository.BookRepository;
import com.foleybooks.catalog.common.PageEnvelope;
import com.foleybooks.catalog.common.PageMeta;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The FR-06 browse rule (CA-06, D-07), owned here exactly where C8 wants it —
 * never in the controller, never in the repository. Three numbers define it:
 * default size 20, max size 100, default sort title ascending. Which page a
 * request actually gets is the clamping of LC-11, decided <em>after</em> the
 * first fetch: the repository's own {@code Page} metadata says how many pages
 * exist, so a page past the last one is re-read as the last page (or as page 0
 * of an empty catalog) instead of serving a fabricated empty window — the
 * client's {@code page.number} always names the page it received.
 *
 * <p>Malformed non-numeric parameters never reach this class: the MVC binder
 * rejects them with a 400 at the boundary (LC-28, D-15). The projection goes
 * through {@link BookMapper#toResponseList} (ADR-009: the mapper owns the
 * shape at every cardinality) inside the read-only transaction, so the lazy
 * category is still resolvable ({@code open-in-view} is off), and the
 * {@link PageMeta} is assembled here — the wire records in {@code common}
 * never learn Spring Data exists (C7). No entity escapes (C9).
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
    public PageEnvelope<BookResponse> listBooks(int page, int size, BookSort sort) {
        Sort effectiveSort = toSpringSort(sort);
        int effectiveSize = clampSize(size);
        int requestedPage = Math.max(page, 0);
        Page<Book> books = bookRepository.findAll(PageRequest.of(requestedPage, effectiveSize, effectiveSort));
        if (requestedPage > 0 && !books.hasContent()) {
            books = bookRepository.findAll(PageRequest.of(lastPageIndex(books), effectiveSize, effectiveSort));
        }
        return new PageEnvelope<>(bookMapper.toResponseList(books.getContent()), pageMeta(books));
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
