package com.foleybooks.order.cart.service;

import com.foleybooks.order.cart.api.CartResponse;
import java.util.UUID;

/**
 * Both halves of the cart behind {@code /api/v1/cart} (FR-10..FR-13). The cart
 * is the only state order-service owns (ADR-004): what is stored here is
 * <em>intent</em> — "this user wants N of this book" — never product data,
 * because price, stock and identity live in {@code catalog_db} and are read
 * live per ADR-005. The caller is named by the authenticated {@code sub}
 * claim — never by a client-named id, so a request can address no cart but
 * its owner's (ADR-004).
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

    /**
     * Read the user's cart enriched with live catalog data (FR-11, plan §4's
     * {@code cart.read}): every stored line is re-validated on <b>every</b>
     * read against what catalog says <em>now</em> (D-08, D-10) — a line whose
     * book no longer exists is flagged {@code available=false} (LC-14) and an
     * overdrawn line {@code insufficientStock} (LC-30); flagged lines stay
     * visible but contribute nothing to the server-computed {@code total}
     * (D-10). The product data arrives through ADR-005's batch: one keyed
     * lookup for the whole cart, never one-per-line (NFR-02).
     *
     * <p>Reading is side-effect-free: a user with no cart gets the empty cart
     * (FR-11's empty state), and nothing is ever created, clamped or deleted
     * here (ADR-004: flags are computed at read time, never written back). A
     * catalog that cannot be reached stays a transport failure surfaced to
     * {@code GlobalExceptionHandler} — never laundered into fabricated flags
     * (ADR-005: absence is data, unavailability is an error).
     *
     * @param userId owner taken from the validated token's {@code sub} claim
     * @return the enriched cart: lines in repository order, exact scale-2
     *         {@code total} over the unflagged lines only, currency {@code EUR}
     */
    CartResponse read(UUID userId);
}
