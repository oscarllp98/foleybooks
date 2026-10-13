package com.foleybooks.catalog.book.service;

import com.foleybooks.catalog.common.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The 404 answer of {@code GET /api/v1/books/{id}} (FR-07, CA-09): a
 * syntactically valid public identifier (AGENTS.md §6) that matches no row in
 * catalog_db. Distinct from a malformed id, which the MVC binder rejects as a
 * 400 validation ProblemDetail before the service is reached — the same
 * unparseable-versus-unknown asymmetry CA-08 established for the
 * {@code categoryId} query parameter (LC-28).
 *
 * <p>The body carries no candidate suggestions and never reveals anything about
 * rows that do exist: catalog reads are public (C22), so the leak risk the auth
 * endpoints guard against (NFR-01) does not apply to a book id. The requested id
 * is echoed back as the {@code bookId} property for NFR-06 traceability — which
 * of several in-flight requests failed, and the same id a {@code traceId}-correlated
 * log line shows — following plan §2's extra-property shape, {@code
 * availableStock} on the cart's 422 and {@code resendHint} on FR-02's 410.
 */
public final class BookNotFoundException extends ApiException {

    private BookNotFoundException(UUID bookId) {
        super(HttpStatus.NOT_FOUND,
                "book-not-found",
                "Book not found",
                "No book exists with the given id.",
                Map.of("bookId", bookId.toString()));
    }

    /** The id resolved to a well-formed UUID but to no book row (FR-07). */
    public static BookNotFoundException forId(UUID bookId) {
        return new BookNotFoundException(bookId);
    }
}
