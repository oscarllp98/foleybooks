package com.foleybooks.order.cart.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Add-a-book payload for {@code POST /api/v1/cart/items} (FR-10, plan §2 — the
 * request half of {@code AddItemRequest → 201 CartResponse}). The book is named
 * by its public catalog UUID (AGENTS.md §6) and the quantity is required:
 * FR-10's "default 1" is what the frontend's selector pre-fills, not a missing
 * field the server may guess — a cart write is intent, and an absent quantity
 * states none (C23). {@code @Min(1)} rejects zero and negatives as the 400
 * {@code errors[]} field list (LC-16's validation half) because unlike PATCH
 * this verb has no zero semantics: removing belongs to PATCH 0 / DELETE, and
 * silently adding nothing for a zero would be a fake success. The upper bound
 * is deliberately NOT here: live stock is catalog's truth (C18), so "above
 * stock" is the service's 422 with the bound it just read (LC-12, LC-16), not
 * a boundary guess that goes stale between catalog's price edits — the same
 * asymmetry that makes the pagination clamp live in the service, not the binder.
 */
public record AddItemRequest(

        @NotNull(message = "is required")
        UUID bookId,

        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        Integer quantity) {
}
