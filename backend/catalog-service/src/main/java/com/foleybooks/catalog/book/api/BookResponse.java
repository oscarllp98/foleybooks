package com.foleybooks.catalog.book.api;

import com.foleybooks.catalog.category.api.CategoryResponse;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One book as the catalog answers with — the plan §2 contract behind
 * {@code GET /books}, {@code GET /books/{id}} (FR-06, FR-07) and
 * {@code GET /books/batch} (D-10): identity, the listing fields, the FR-07
 * {@link Availability} badge and the embedded category. The field list is a
 * reviewed contract, pinned here and in ADR-009.
 *
 * <p>{@code availability} is never read from a column: it is derived from
 * {@code stockQuantity} by {@link
 * com.foleybooks.catalog.book.service.AvailabilityPolicy} (D-09, C8) and wired
 * by {@link com.foleybooks.catalog.book.mapping.BookMapper}, so the badge and
 * the quantity can never be observed disagreeing. Both are published on
 * purpose — FR-07 renders the badge, while FR-10 needs the exact upper bound of
 * the quantity selector (1..stock, LC-12) and the cart re-validates every line
 * against it on read (FR-11, LC-30), which is what {@code /books/batch} feeds.
 * Publishing an exact inventory number on a public (C22) read model is a
 * deliberate trade-off, recorded in ADR-009, not an oversight.
 *
 * <p>{@code price} stays an exact EUR decimal at scale 2, serialized as a JSON
 * number (D-08, NFR-07). Nothing else about the row escapes: no audit columns,
 * no FK bookkeeping — entities never cross an HTTP boundary (C9).
 */
public record BookResponse(
        UUID id,
        String title,
        String author,
        String isbn,
        BigDecimal price,
        String coverUrl,
        Availability availability,
        int stockQuantity,
        CategoryResponse category) {
}
