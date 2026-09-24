package com.foleybooks.order.cart.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.foleybooks.order.cart.service.BookNotFoundException;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * The 404 decode of ADR-005's "absence is data; unavailability is an error":
 * catalog's {@code urn:foley-books:problem:book-not-found} (the
 * {@code GET /api/v1/books/{id}} answer to a well-formed id naming no row) is
 * translated into the order-domain {@link BookNotFoundException} so the service
 * layer never sees a transport type (C7, C8) — this is the concrete form of plan
 * §4's {@code if book == null: return 404}. The {@code GlobalExceptionHandler}
 * advice then renders the client-facing 404 for {@code POST /cart/items}
 * (plan §2, FR-10).
 *
 * <p>The mapping is deliberately narrow, per ADR-005's "that 404": only a body
 * carrying the {@code book-not-found} URN — or, when the body is absent or
 * unreadable, a request whose path is the {@code /api/v1/books/{uuid}} shape —
 * counts. Any other 404 (a proxy or route-level miss, a foreign problem body)
 * says nothing about books and keeps the {@link ErrorDecoder.Default} behavior,
 * surfacing as a {@code FeignException} the advice renders as 503
 * {@code catalog-unavailable} (ADR-005, D-15, NFR-06). Collapsing "the book is
 * gone" with "something answered 404" or "I could not ask" would flag-and-exclude
 * cart lines on a fabricated premise and hand the user a wrong total (NFR-07) —
 * the load-bearing prohibition this class exists to enforce.
 *
 * <p>The requested id is recovered best-effort, first from the {@code bookId}
 * property of catalog's ProblemDetail body (ADR-009's extra-property shape),
 * then from the request path; if neither parses the exception still carries the
 * right status without a made-up id. The upstream body is read only for that
 * extraction and never re-emitted (C24).
 */
public class CatalogErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(CatalogErrorDecoder.class);

    /** The only catalog error this decoder is licensed to translate (ADR-005, ADR-009). */
    private static final String BOOK_NOT_FOUND_TYPE = "urn:foley-books:problem:book-not-found";

    /** Catalog's 404 shape: {@code GET /api/v1/books/{id}} with a UUID-typed path variable. */
    private static final Pattern BOOK_PATH = Pattern.compile("/api/v1/books/([0-9a-fA-F-]{36})$");

    private final ErrorDecoder defaultDecoder = new Default();

    private final ObjectMapper bodyMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.status() == HttpStatus.NOT_FOUND.value()) {
            Optional<CatalogProblem> problem = readProblem(response);
            if (problem.isPresent() ? problem.get().isBookNotFound() : namesBookPath(response)) {
                UUID bookId = problem.flatMap(CatalogProblem::bookId)
                        .or(() -> pathBookId(response))
                        .orElse(null);
                log.debug("Catalog answered book-not-found for {} (bookId={})",
                        methodKey, bookId != null ? bookId : "unrecoverable");
                return bookId != null ? BookNotFoundException.forId(bookId) : BookNotFoundException.unknownBook();
            }
        }
        return defaultDecoder.decode(methodKey, response);
    }

    /**
     * Best-effort parse of catalog's ProblemDetail. Empty when the body is absent,
     * unreadable or not JSON — an unreadable 404 falls back to the path shape, so
     * a proxy that eats the body cannot silently upgrade a foreign 404 into
     * book-not-found either.
     */
    private Optional<CatalogProblem> readProblem(Response response) {
        if (response.body() == null) {
            return Optional.empty();
        }
        try (InputStream in = response.body().asInputStream()) {
            JsonNode body = bodyMapper.readTree(in);
            JsonNode type = body.get("type");
            JsonNode bookId = body.get("bookId");
            return Optional.of(new CatalogProblem(
                    type != null && type.isTextual() ? type.asText() : null,
                    bookId != null && bookId.isTextual() ? parseUuid(bookId.asText()) : Optional.empty()));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    private boolean namesBookPath(Response response) {
        return pathBookId(response).isPresent();
    }

    private Optional<UUID> pathBookId(Response response) {
        if (response.request() == null || response.request().url() == null) {
            return Optional.empty();
        }
        Matcher matcher = BOOK_PATH.matcher(stripQueryAndSlash(response.request().url()));
        return matcher.find() ? parseUuid(matcher.group(1)) : Optional.empty();
    }

    private static String stripQueryAndSlash(String url) {
        int queryStart = url.indexOf('?');
        String withoutQuery = queryStart >= 0 ? url.substring(0, queryStart) : url;
        return withoutQuery.endsWith("/") ? withoutQuery.substring(0, withoutQuery.length() - 1) : withoutQuery;
    }

    private static Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** The two ProblemDetail properties this decoder reads; nothing else about the body is trusted. */
    private record CatalogProblem(String type, Optional<UUID> bookId) {

        boolean isBookNotFound() {
            return BOOK_NOT_FOUND_TYPE.equals(type);
        }
    }
}
