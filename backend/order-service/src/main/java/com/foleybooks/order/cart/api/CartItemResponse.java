package com.foleybooks.order.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One enriched cart line as the FR-11 read answers with — the plan §2 cart-JSON
 * contract ({@code bookId, title, author, coverUrl, unitPrice, quantity,
 * lineTotal, stockQuantity, available, insufficientStock}), realized through
 * ADR-005's two-source join: {@code quantity} comes from order_db (the stored
 * intent, ADR-004) and everything else comes live from the catalog batch answer
 * — never from a copy, because price and stock are catalog's single truth
 * (D-08, NFR-07).
 *
 * <p>The line is addressed by {@code bookId}, not by {@code cart_items.id}: the
 * internal row ids never surface (ADR-004), while the book id is the public
 * UUID (AGENTS.md §6) that {@code PATCH}/{@code DELETE /cart/items/{bookId}}
 * (plan §2) will target.
 *
 * <p>The two flags are derived per line at read time (C8 — they are service
 * rules, never stored): {@code available=false} means the catalog batch answer
 * held no entry for this book (LC-14, a defensive state the static MVP catalog
 * only produces in fixtures); {@code insufficientStock=true} means the book
 * exists but the live {@code stockQuantity} is below the requested
 * {@code quantity} (LC-30). A flagged-insufficient line stays fully populated —
 * the user must see the real price and the real stock to act on it (FR-11) —
 * and its true {@code lineTotal} is reported; it is the cart-level {@code
 * total} that excludes flagged lines (D-10).
 *
 * <p>An unavailable line carries nothing but {@code bookId}, {@code quantity}
 * and the flag: every catalog-sourced field ({@code title}, {@code author},
 * {@code coverUrl}, {@code unitPrice}, {@code lineTotal}, {@code
 * stockQuantity}) is {@code null} — the honest encoding of "there is no book
 * to read" (ADR-005). Not {@code 0.00}, not the empty string, not a fabricated
 * bound: summing or displaying made-up money would be exactly the second,
 * drift-prone truth ADR-004 refuses to store.
 *
 * <p>{@code unitPrice} and {@code lineTotal} are exact EUR decimals at scale 2
 * serialized as JSON numbers (D-08); {@code lineTotal} is
 * {@code unitPrice × quantity}, an integer multiplier over a scale-2 price, so
 * it is exact without any rounding step (NFR-07).
 */
public record CartItemResponse(
        UUID bookId,
        String title,
        String author,
        String coverUrl,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal,
        Integer stockQuantity,
        boolean available,
        boolean insufficientStock) {
}
