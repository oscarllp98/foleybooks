package com.foleybooks.catalog.book.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.catalog.book.api.Availability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The D-09 thresholds (FR-07, plan §6.1 {@code availability_thresholds}) as the
 * pure function they are: no Spring context, no database, no mocks. The
 * derivation lives in the service layer (C8, ADR-009) while the badge stays a
 * constants-only enum, and every quantity asserted here is a real CA-04 seed
 * bucket edge — 0 (The Great Gatsby), 5 (The Pragmatic Programmer, the LOW
 * edge), 6 (Good to Great, the first IN_STOCK) — so the rule is judged against
 * the data FR-14 actually ships.
 */
class AvailabilityPolicyTest {

    private final AvailabilityPolicy policy = new AvailabilityPolicy();

    @ParameterizedTest(name = "availability_thresholds: {0} -> {1}")
    @CsvSource({
            "0, OUT_OF_STOCK",
            "1, LOW_STOCK",
            "5, LOW_STOCK",
            "6, IN_STOCK",
            "7, IN_STOCK",
            "14, IN_STOCK",
            "2147483647, IN_STOCK"
    })
    void forStock_whenQuantitySitsOnEitherSideOfEachThreshold_returnsD09Badge(
            int stockQuantity, Availability expected) {
        assertThat(policy.forStock(stockQuantity)).isEqualTo(expected);
    }

    @Test
    void forStock_whenQuantityIsTheLowStockUpperBound_returnsLowStock() {
        // FR-07 reads "1–5", inclusive of both edges, so the constant and the
        // spec cannot drift apart unnoticed.
        assertThat(policy.forStock(AvailabilityPolicy.LOW_STOCK_MAX_QUANTITY)).isEqualTo(Availability.LOW_STOCK);
    }

    @Test
    void forStock_whenQuantityJustExceedsLowStockUpperBound_returnsInStock() {
        assertThat(policy.forStock(AvailabilityPolicy.LOW_STOCK_MAX_QUANTITY + 1)).isEqualTo(Availability.IN_STOCK);
    }

    @Test
    void forStock_whenQuantityNegative_throwsInsteadOfQuietlyReportingOutOfStock() {
        // ck_books_stock (ADR-003) makes this unreachable through the database:
        // a negative argument is a caller bug, and laundering it into
        // OUT_OF_STOCK would hide a real inventory number behind a plausible badge.
        assertThatThrownBy(() -> policy.forStock(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }
}
