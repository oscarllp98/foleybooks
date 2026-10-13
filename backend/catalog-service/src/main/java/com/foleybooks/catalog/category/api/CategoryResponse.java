package com.foleybooks.catalog.category.api;

import java.util.UUID;

/**
 * A category as the API exposes it (FR-09): public UUID plus name — the
 * {@code { id, name }} object embedded in every {@link
 * com.foleybooks.catalog.book.api.BookResponse} (plan §2, ADR-003) and the
 * element shape of {@code GET /categories} (CA-10). Audit columns stay behind
 * the entity boundary (C9): when a category row was created is not a
 * shopper-visible fact, and the frontend's category filter needs nothing beyond
 * id + name.
 */
public record CategoryResponse(UUID id, String name) {
}
