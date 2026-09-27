package com.foleybooks.order.cart.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Change-a-line payload for {@code PATCH /api/v1/cart/items/{bookId}} (FR-12,
 * plan §2 — the request half of {@code UpdateQuantityRequest → 200 CartResponse}).
 * The line is addressed by the path's book id, so the body carries only the
 * <em>resulting</em> quantity: a set, not FR-10's delta, because FR-12 says the
 * user "changes a line's quantity" to a named value, which is what makes the
 * operation safe to replay.
 *
 * <p>Zero is legal and meaningful here — "setting quantity to 0 explicitly
 * removes the line" (FR-12) — so the floor is {@code @Min(0)}, and every
 * negative is a 400 {@code errors[]} field error: "no other out-of-range value
 * is accepted" (FR-12, LC-16's validation half). A non-numeric or
 * int-overflowing value never reaches validation at all: Jackson refuses it as
 * the shared 400 malformed-body ProblemDetail (C23). The upper bound lives in
 * the service's live stock gate (LC-12: 422 with {@code availableStock}), the
 * same catalog-truth asymmetry {@link AddItemRequest} documents. An absent
 * field is rejected rather than defaulted: an update with no named result
 * states no intent (C23).
 */
public record UpdateQuantityRequest(

        @NotNull(message = "is required")
        @Min(value = 0, message = "must not be negative")
        Integer quantity) {
}
