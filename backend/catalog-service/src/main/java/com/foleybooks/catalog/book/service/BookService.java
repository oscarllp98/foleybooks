package com.foleybooks.catalog.book.service;

import com.foleybooks.catalog.book.api.BookResponse;
import com.foleybooks.catalog.common.PageEnvelope;

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
     * @param page zero-based page index to serve, clamped into range
     * @param size page size to serve, clamped to {@code 1..100}, default 20
     * @return the AGENTS.md §6 envelope: mapped books plus the metadata of the
     *         page actually served
     */
    PageEnvelope<BookResponse> listBooks(int page, int size);
}
