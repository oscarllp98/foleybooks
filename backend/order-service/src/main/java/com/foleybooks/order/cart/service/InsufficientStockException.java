package com.foleybooks.order.cart.service;

import com.foleybooks.order.common.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The 422 answer of the cart write paths (FR-10/LC-12, FR-12/LC-16, plan §2):
 * the <em>request</em> quantity a caller asks to add is outside what
 * catalog-service currently has in stock — either non-positive or greater
 * than the live figure. Raised by {@code CartServiceImpl} against the live
 * {@code BookDto} from the east-west lookup (ADR-005), never against a stored
 * copy, because stock is catalog's single truth and a cached bound is exactly
 * the drift NFR-07 forbids. It is deliberately the request gate alone: once a
 * request clears it, a re-add sums onto the existing line capped by stock
 * (LC-13) and never reaches this exception — over-adding is capped, not
 * rejected.
 *
 * <p>Carries the {@code availableStock} property — the plan §2 extra-property
 * shape ({@code urn:foley-books:problem:insufficient-stock}, detail "Only 3
 * units left.") that lets the UI show how many the user can actually have. The
 * value is an {@code int} by contract (catalog's {@code stock_quantity},
 * ADR-003/ADR-009), so {@code detail} is phrased from the same number the
 * property carries: the response can never state one bound and show another.
 *
 * <p>An out-of-stock book (stock 0) is the same business rule with a zero
 * bound, not a different status: LC-12 covers "an out-of-stock book or
 * quantity above stock" in one rejected-with-available-stock answer. Rendering
 * through {@code GlobalExceptionHandler} adds the {@code traceId} and
 * {@code instance} (D-15, NFR-06).
 */
public final class InsufficientStockException extends ApiException {

    private InsufficientStockException(int availableStock) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "insufficient-stock",
                "Insufficient stock",
                availableStock == 0
                        ? "This book is out of stock."
                        : "Only " + availableStock + (availableStock == 1 ? " unit left." : " units left."),
                Map.of("availableStock", availableStock));
    }

    /** The requested quantity exceeds (or empties) the live stock catalog just reported (LC-12, LC-16). */
    public static InsufficientStockException forStock(int availableStock) {
        return new InsufficientStockException(availableStock);
    }
}
