package com.foleybooks.order.cart.service;

import com.foleybooks.order.common.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The 404 answer of {@code PATCH /api/v1/cart/items/{bookId} (FR-12): a positive
 * quantity asks to <em>change</em> a line the user's cart does not hold. FR-12
 * speaks only of changing "a line's" quantity — creating one is FR-10's POST,
 * never a side effect of an update — and FR-13 makes DELETE idempotent precisely
 * because removal has nothing to address. An update does: the addressed line is
 * the missing resource, and AGENTS.md §6's standard status for missing is 404,
 * so the plan §2 row ("200 CartResponse / 422") abbreviates, exactly as its
 * other rows abbreviate 400/401.
 *
 * <p>The 404 is answered locally, before the east-west hop: whether the catalog
 * still stocks the book is irrelevant when there is no line to restock — and a
 * check that ran first could also launder the LC-14 vanished-book state into
 * {@code book-not-found} for a line that never existed (ADR-005). The
 * well-formed-but-absent answer therefore precedes the stock gate, and the two
 * 404s stay honest: this one names the missing cart line, {@link
 * BookNotFoundException} (via {@code CatalogErrorDecoder}) names the missing
 * book behind a line that does exist.
 *
 * <p>Carries the requested {@code bookId} as an extra property — plan §2's
 * established echo shape ({@code availableStock}, {@code resendHint},
 * catalog's {@code bookId}) — for NFR-06 traceability, not enumeration: the
 * cart is private per-user state, so this can leak nothing public.
 */
public final class CartLineNotFoundException extends ApiException {

    private CartLineNotFoundException(UUID bookId) {
        super(HttpStatus.NOT_FOUND,
                "cart-line-not-found",
                "Cart line not found",
                "The cart holds no line for the given book.",
                bookId != null ? Map.of("bookId", bookId.toString()) : Map.of());
    }

    /** The user's cart contains no line for this book id (FR-12). */
    public static CartLineNotFoundException forBookId(UUID bookId) {
        return new CartLineNotFoundException(bookId);
    }
}
