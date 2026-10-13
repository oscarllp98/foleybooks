package com.foleybooks.order.cart.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.foleybooks.order.cart.service.BookNotFoundException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins ADR-005's load-bearing split at the transport boundary: catalog's specific
 * 404 ({@code book-not-found}, ADR-009) decodes into the domain
 * {@link BookNotFoundException} with the id echoed back, while every other status
 * stays a {@code FeignException} for {@code GlobalExceptionHandler} to render as
 * 503 {@code catalog-unavailable}. "The book is gone" must never absorb "the
 * catalog could not be asked" — the first zeroes a cart line honestly, the
 * second would fabricate the whole cart (NFR-07).
 */
class CatalogErrorDecoderTest {

    private static final UUID BOOK_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final String FIND_URL = "http://catalog-service:8082/api/v1/books/" + BOOK_ID;

    private final CatalogErrorDecoder decoder = new CatalogErrorDecoder();

    @Test
    void decode_whenCatalogAnswers404WithBookNotFoundBody_throwsBookNotFoundWithEchoedId() {
        Response response = response(404, FIND_URL, """
                { "type": "urn:foley-books:problem:book-not-found",
                  "title": "Book not found", "status": 404,
                  "detail": "No book exists with the given id.",
                  "instance": "/api/v1/books/3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "traceId": "d4e5f6a7",
                  "bookId": "3fa85f64-5717-4562-b3fc-2c963f66afa6" }
                """);

        BookNotFoundException ex = (BookNotFoundException) decoder.decode("CatalogClient#findBook(UUID)", response);

        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getType()).isEqualTo("urn:foley-books:problem:book-not-found");
        assertThat(ex.getTitle()).isEqualTo("Book not found");
        assertThat(ex.getDetail()).isEqualTo("No book exists with the given id.");
        assertThat(ex.getProperties()).containsEntry("bookId", BOOK_ID.toString());
    }

    @Test
    void decode_when404BodyIsUnreadableButUrlNamesTheBook_recoversIdFromRequestPath() {
        Response response = response(404, FIND_URL, "<html>proxy ate the problem detail</html>");

        BookNotFoundException ex = (BookNotFoundException) decoder.decode("CatalogClient#findBook(UUID)", response);

        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getProperties()).containsEntry("bookId", BOOK_ID.toString());
    }

    @Test
    void decode_whenBookNotFoundBodyOmitsTheId_echoesNothingInsteadOfGuessingOne() {
        // A degenerate catalog body: the URN proves the verdict, but neither the
        // body nor a (here rewritten-by-a-proxy, no-UUID) path supplies an id —
        // never invent one.
        Response response = response(404, "http://catalog-service:8082/api/v1/books", """
                { "type": "urn:foley-books:problem:book-not-found",
                  "title": "Book not found", "status": 404 }
                """);

        BookNotFoundException ex = (BookNotFoundException) decoder.decode("CatalogClient#findBook(UUID)", response);

        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getProperties()).doesNotContainKey("bookId");
    }

    @Test
    void decode_when404CarriesAForeignProblemUrnOnABookPath_propagatesAsFeignException() {
        // A 404 that does not say book-not-found says nothing about books — the
        // decoder may not translate it (ADR-005's "that 404"), even on a book URL.
        Response response = response(404, FIND_URL, """
                { "type": "urn:foley-books:problem:resource-not-found",
                  "title": "Resource not found", "status": 404 }
                """);

        Exception decoded = decoder.decode("CatalogClient#findBook(UUID)", response);

        assertThat(decoded).isInstanceOf(FeignException.class);
        assertThat(((FeignException) decoded).status()).isEqualTo(404);
    }

    @Test
    void decode_when404CarriesNoBodyAndNoBookPath_propagatesAsFeignException() {
        // CA-11 guarantees the batch never 404s for a missing entry, so a 404 on
        // this URL is a foreign/route answer: not a book verdict, not a
        // BookNotFoundException with a missing id.
        Response response = response(404, "http://catalog-service:8082/api/v1/books/batch", null);

        Exception decoded = decoder.decode("CatalogClient#batchBooks(Collection)", response);

        assertThat(decoded).isInstanceOf(FeignException.class);
        assertThat(((FeignException) decoded).status()).isEqualTo(404);
    }

    @Test
    void decode_whenCatalogAnswers503_staysAFeignExceptionNotBookNotFound() {
        Response response = response(503, FIND_URL, "{ \"detail\": \"no healthy upstream\" }");

        // Feign's ErrorDecoder#decode never throws: it returns the exception to raise.
        Exception decoded = decoder.decode("CatalogClient#findBook(UUID)", response);

        assertThat(decoded).isInstanceOf(FeignException.ServiceUnavailable.class);
        assertThat(((FeignException) decoded).status()).isEqualTo(503);
    }

    @Test
    void decode_whenCatalogAnswers400OnMalformedInput_propagatesAsFeignException() {
        Response response = response(400, "http://catalog-service:8082/api/v1/books/not-a-uuid", """
                { "type": "urn:foley-books:problem:validation", "status": 400 }
                """);

        Exception decoded = decoder.decode("CatalogClient#findBook(UUID)", response);

        assertThat(decoded).isInstanceOf(FeignException.class);
        assertThat(((FeignException) decoded).status()).isEqualTo(400);
    }

    private static Response response(int status, String url, String body) {
        Request request = Request.create(Request.HttpMethod.GET, url, jsonHeaders(),
                Request.Body.empty(), null);
        Response.Builder builder = Response.builder()
                .status(status)
                .reason("status " + status)
                .request(request)
                .headers(jsonHeaders());
        return (body != null ? builder.body(body, StandardCharsets.UTF_8) : builder).build();
    }

    private static Map<String, Collection<String>> jsonHeaders() {
        return Map.of("Content-Type", List.of("application/json"));
    }
}
