package com.foleybooks.order.cart.service;

import com.foleybooks.order.common.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The 404 answer of {@code POST /api/v1/cart/items} (FR-10, plan §2): a
 * well-formed book id that catalog-service says names no book. Raised at the
 * transport boundary — {@code cart/client/CatalogErrorDecoder} decodes catalog's
 * {@code urn:foley-books:problem:book-not-found} into this order-domain type so
 * {@code CartService} never handles a Feign type (C7, C8) and simply lets it
 * propagate to {@code GlobalExceptionHandler}, the concrete form of plan §4's
 * {@code if book == null: return 404}.
 *
 * <p>Deliberately distinct from a catalog that is <em>unreachable</em>: that
 * stays a {@code feign.FeignException} rendered as 503
 * {@code catalog-unavailable}, because "the book is gone" and "I could not ask"
 * are different facts and only the first may zero a cart line (ADR-005, NFR-07).
 *
 * <p>The requested id is echoed back as the {@code bookId} property, the same
 * extra-property shape catalog publishes for its own 404 (ADR-009) and plan §2
 * establishes for {@code availableStock} and {@code resendHint} — kept for
 * NFR-06 traceability, not for enumeration (catalog reads are public, C22). A
 * response the decoder cannot parse carries no property rather than a guess.
 */
public final class BookNotFoundException extends ApiException {

    private BookNotFoundException(UUID bookId) {
        super(HttpStatus.NOT_FOUND,
                "book-not-found",
                "Book not found",
                "No book exists with the given id.",
                bookId != null ? Map.of("bookId", bookId.toString()) : Map.of());
    }

    /** Catalog answered 404 for this well-formed id: the book row does not exist (FR-10). */
    public static BookNotFoundException forId(UUID bookId) {
        return new BookNotFoundException(bookId);
    }

    /** Catalog answered 404 but the requested id could not be recovered from the call. */
    public static BookNotFoundException unknownBook() {
        return new BookNotFoundException(null);
    }
}
