package com.foleybooks.catalog.category.service;

import com.foleybooks.catalog.category.api.CategoryResponse;
import com.foleybooks.catalog.common.PageEnvelope;

/**
 * Read operations behind {@code /api/v1/categories} (FR-09). The MVP catalog is
 * a seeded, read-only model (spec §6), so — like {@link
 * com.foleybooks.catalog.book.service.BookService} — every method here answers
 * questions about the store, never changes it.
 */
public interface CategoryService {

    /** AGENTS.md §6: the size served when none is requested; also the LC-11 target of {@code size < 1}. */
    int DEFAULT_SIZE = 20;

    /** AGENTS.md §6: hard ceiling for any requested size (NFR-02); the LC-11 target of {@code size > 100}. */
    int MAX_SIZE = 100;

    /** Textual {@link #DEFAULT_SIZE} for the MVC binder — one owner for the number the controller defaults to. */
    String DEFAULT_SIZE_TEXT = "20";

    /**
     * One page of the category list (FR-09, CA-10): page 0, size 20 when
     * nothing is asked for, sorted by name ascending so the list — and the
     * filter dropdown the frontend builds from it (FE-12) — is deterministic
     * across requests.
     *
     * <p>The paging rules are the AGENTS.md §6/LC-11 ones every paged read in
     * this service already honors, not a category-specific invention: a negative
     * page serves page 0, a size below 1 serves 20, a size above 100 serves
     * 100, and a page past the last one serves the last page (or page 0 of an
     * empty list). Malformed non-numeric values never reach this method — the
     * MVC binder answers them with a 400 validation ProblemDetail at the
     * boundary (LC-28).
     *
     * <p>An empty {@code categories} table is a 200 with an empty envelope,
     * never an error (FR-09, LC-31): "the category list is available" is
     * satisfied exactly as much by {@code totalElements: 0} as by a full page.
     *
     * @param page zero-based page index to serve, clamped into range
     * @param size page size to serve, clamped to {@code 1..100}, default 20
     * @return the AGENTS.md §6 envelope: mapped categories plus the metadata
     *         of the page actually served
     */
    PageEnvelope<CategoryResponse> listCategories(int page, int size);
}
