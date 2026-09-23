package com.foleybooks.catalog.book.service;

import com.foleybooks.catalog.book.api.BookResponse;
import com.foleybooks.catalog.book.api.BookSort;
import com.foleybooks.catalog.common.PageEnvelope;
import java.util.UUID;

/**
 * Read operations behind {@code /api/v1/books} (FR-06). The MVP catalog is a
 * seeded, read-only model (spec §6), so every method here answers questions
 * about the store — it never changes it.
 */
public interface BookService {

    /** AGENTS.md §6 / FR-06: the size served when none is requested; also the LC-11 target of {@code size < 1}. */
    int DEFAULT_SIZE = 20;

    /** FR-06: hard ceiling for any requested size (NFR-02); the LC-11 target of {@code size > 100}. */
    int MAX_SIZE = 100;

    /** Textual {@link #DEFAULT_SIZE} for the MVC binder — one owner for the number the controller defaults to. */
    String DEFAULT_SIZE_TEXT = "20";

    /**
     * One page of the catalog, sorted by title ascending by default (FR-06):
     * page 0, size 20 when nothing is asked for — the hottest read in the
     * system (public, NFR-02), so a size cap is a rule, not a courtesy.
     *
     * <p>Numeric pagination values are clamped, never rejected (LC-11, D-07):
     * a negative page serves page 0, a size below 1 serves 20, a size above
     * 100 serves 100, and a page past the last one serves the last page (or
     * page 0 of an empty catalog). Malformed non-numeric values never reach
     * this method — the MVC binder answers them with a 400 validation
     * ProblemDetail at the boundary (LC-28).
     *
     * <p>The {@code sort} selection (CA-07, FR-06) is likewise already validated
     * at the boundary: {@code BookSortConverter} whitelists it to {@code title}
     * or {@code price} with an ascending/descending direction, so anything that
     * arrives here is safe to feed straight into the query. A {@code null} sort —
     * the parameter omitted or left blank — falls back to the FR-06 default of
     * title ascending.
     *
     * <p>{@code search} and {@code categoryId} are the CA-08 browse filter
     * (FR-08, FR-09, D-06): {@code search} has already been trimmed and read as
     * "no filter" when blank, at the boundary and per C23 — the same shape of
     * treatment {@code sort} and auth's email input already get — and
     * {@code categoryId} has already been type-checked there. What remains here
     * and downstream — case-insensitive matching, LIKE metacharacter escaping,
     * and {@code AND} composition — is query-execution mechanics the repository
     * layer owns ({@code BookSpecifications}), not validation. Neither filter is
     * an error condition when it matches nothing: {@code search} finds no book,
     * or {@code categoryId} names no category, and FR-08/LC-10 and FR-09/LC-31
     * both require the same empty page any other no-result browse already serves.
     * Pagination and sorting apply on top of the filter exactly as they do to
     * the unfiltered catalog (FR-08's "same rules as FR-06"), including the
     * past-the-last-page clamp.
     *
     * @param page       zero-based page index to serve, clamped into range
     * @param size       page size to serve, clamped to {@code 1..100}, default 20
     * @param sort       validated sort selection, or {@code null} for the default
     * @param search     boundary-trimmed term over title and author, {@code null} for none
     * @param categoryId exact category to browse within, or {@code null} for the whole catalog
     * @return the AGENTS.md §6 envelope: mapped books plus the metadata of the
     *         page actually served
     */
    PageEnvelope<BookResponse> listBooks(int page, int size, BookSort sort, String search, UUID categoryId);

    /**
     * One book's detail (FR-07, CA-09): the full {@link BookResponse} shape —
     * identity, listing fields, the derived {@link
     * com.foleybooks.catalog.book.api.Availability} badge and the embedded
     * category — the same projection the list serves, so a card and the page it
     * links to can never disagree about a price or a badge (ADR-009).
     *
     * <p>Only one outcome is possible for an id that already parsed: the row is
     * found, or it is not and {@link BookNotFoundException} answers 404. A
     * malformed id is never this method's problem — the MVC binder rejects it as
     * a 400 validation error at the boundary (LC-28), exactly as it does for a
     * malformed {@code page} or {@code categoryId}.
     *
     * @param id public identifier of the book to read, already parsed by the binder
     * @return the FR-07 detail record
     * @throws BookNotFoundException if no book exists with that id
     */
    BookResponse getBook(UUID id);
}
