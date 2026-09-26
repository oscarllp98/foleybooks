package com.foleybooks.order.cart.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * The whole cart as FR-11 answers with — the plan §2 cart-JSON contract
 * ({@code items}, {@code total}, {@code currency}) and the only read view
 * order-service owns: an enriched {@link CartItemResponse} per stored line, in
 * the order the repository returned them (ADR-005: batch answers carry no
 * contractual order, so line order is the local read's order, never the
 * catalog's).
 *
 * <p>{@code total} is computed server-side from live catalog data (D-08: the
 * frontend only renders money, it never derives it) and is the sum of the
 * {@code lineTotal}s of lines that pass <b>both</b> stock gates — an
 * unavailable line (LC-14) has no price to sum, and an insufficient line
 * (LC-30) is money the user cannot actually spend today, so flagged lines
 * contribute nothing (D-10). The result is an exact EUR decimal at scale 2
 * (NFR-07): scale-2 addends summed exactly, with a final scale-2 guarantee that
 * is a serialization invariant, never a rounding event.
 *
 * <p>An empty cart — no rows at all, or a cart whose lines were all removed —
 * is a valid state, not an error (FR-11's empty state, ADR-004: the FR-14 demo
 * accounts start here): {@code items} is the empty list and {@code total} is
 * {@code 0.00}. {@code currency} is the system-wide constant {@code "EUR"}
 * carried on the response, never a stored column (ADR-004).
 */
public record CartResponse(
        List<CartItemResponse> items,
        BigDecimal total,
        String currency) {
}
