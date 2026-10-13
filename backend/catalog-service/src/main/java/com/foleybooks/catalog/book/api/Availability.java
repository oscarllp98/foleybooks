package com.foleybooks.catalog.book.api;

/**
 * The FR-07 availability badge as the API publishes it (ADR-003, ADR-009):
 * {@code 0 → OUT_OF_STOCK}, {@code 1–5 → LOW_STOCK}, {@code > 5 → IN_STOCK}.
 *
 * <p>Like {@code UserStatus} and {@code ConfirmOutcome} in auth-service, this is
 * a constants-only enum: the thresholds are documented here, but D-09 is
 * computed only by
 * {@link com.foleybooks.catalog.book.service.AvailabilityPolicy} (ADR-009) —
 * keeping the rule out of the read model is what lets a future stock change
 * (the deferred checkout spec) move a badge without a second copy of the logic
 * disagreeing with the first. Nothing here is persisted (D-09):
 * {@code stock_quantity} stays the single stored truth.
 */
public enum Availability {

    /** Nothing left to sell; an add-to-cart from this state is rejected (FR-10, LC-12). */
    OUT_OF_STOCK,

    /** Purchasable but scarce — the "only a few left" signal FR-07 renders. */
    LOW_STOCK,

    /** Deep enough that the catalog never needs to warn about it. */
    IN_STOCK
}
