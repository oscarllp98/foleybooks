package com.foleybooks.order.cart.service;

import java.util.UUID;

/**
 * The cart write rules behind {@code /api/v1/cart} (FR-10..FR-13). The cart is
 * the only state order-service owns (ADR-004): what callers get here is
 * <em>intent</em> — "this user wants N of this book" — never product data,
 * because price, stock and identity live in {@code catalog_db} and are read
 * live per ADR-005. The caller is named by the authenticated {@code sub}
 * claim — never by a client-named id, so a request can address no cart but
 * its owner's (ADR-004); the read/enrichment half arrives with {@code read}
 * (OR-07).
 */
public interface CartService {

    /**
     * Add {@code quantity} of {@code bookId} to the user's cart (FR-10): the
     * book's live stock is fetched from catalog-service first — a well-formed
     * id naming no book answers 404, and a quantity outside what the catalog
     * currently has answers 422 with the bound, never a silently smaller cart
     * (LC-12, LC-16). An already-present book sums into its single line,
     * capped by stock — two devices adding the same book can never create two
     * lines or oversell it (LC-13, LC-17).
     *
     * @param userId   owner taken from the validated token's {@code sub} claim
     * @param bookId   catalog public identifier of the book to add
     * @param quantity requested add-time quantity, ≥ 1 (boundary-validated, C23)
     * @return the resulting line's record, holding the quantity actually stored
     * @throws BookNotFoundException     if catalog says the book does not exist
     * @throws InsufficientStockException if the request exceeds live stock
     */
    CartLine add(UUID userId, UUID bookId, int quantity);
}
