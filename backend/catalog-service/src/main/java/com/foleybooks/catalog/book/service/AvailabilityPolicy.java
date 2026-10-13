package com.foleybooks.catalog.book.service;

import com.foleybooks.catalog.book.api.Availability;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

/**
 * The D-09 availability rule (FR-07), owned by the service layer exactly like
 * every other rule in this repository (C8, AGENTS.md §3): 0 units is
 * {@link Availability#OUT_OF_STOCK}, 1–5 is {@link Availability#LOW_STOCK},
 * anything above 5 is {@link Availability#IN_STOCK}. The numbers themselves are
 * spec text (FR-07); only this class is allowed to turn a quantity into a badge.
 *
 * <p>It is exposed as a {@link Named} method so {@link
 * com.foleybooks.catalog.book.mapping.BookMapper} can bind it to the
 * {@code availability} component at build time instead of inventing its own
 * copy (ADR-009), and as a plain component so a future cart or admin rule can
 * ask the same question of the same number. Stateless, no collaborators, no
 * transaction — a pure function, which is what makes CA-05 testable without a
 * database (plan §6.1).
 */
@Component
public class AvailabilityPolicy {

    /**
     * Highest quantity that still reads as low stock; the first quantity above
     * it — 6, exactly the {@code Good to Great} seed row (CA-04) — is already
     * IN_STOCK. The 5/6 pair is therefore the edge every FR-07 assertion uses.
     */
    static final int LOW_STOCK_MAX_QUANTITY = 5;

    /**
     * @param stockQuantity the book's current {@code stock_quantity}
     * @return the FR-07 badge for that quantity
     * @throws IllegalArgumentException if the quantity is negative —
     *         {@code ck_books_stock} (ADR-003) makes that unreachable through
     *         the database, so a negative argument is a caller bug that must
     *         surface instead of being laundered into OUT_OF_STOCK
     */
    @Named("availabilityOfStock")
    public Availability forStock(int stockQuantity) {
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("stockQuantity must not be negative, was " + stockQuantity);
        }
        if (stockQuantity == 0) {
            return Availability.OUT_OF_STOCK;
        }
        return stockQuantity <= LOW_STOCK_MAX_QUANTITY ? Availability.LOW_STOCK : Availability.IN_STOCK;
    }
}
