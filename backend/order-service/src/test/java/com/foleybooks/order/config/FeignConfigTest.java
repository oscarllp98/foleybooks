package com.foleybooks.order.config;

import com.foleybooks.order.cart.client.BookDto;
import feign.Request;
import feign.Response;
import feign.codec.Decoder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ADR-005 codec decision: catalog may add {@code BookResponse} fields at any
 * time (ADR-009), and the consumer-side subset {@code cart/client/BookDto} must still
 * decode — the client mapper is configured FAIL_ON_UNKNOWN_PROPERTIES = false precisely
 * for that. OR-05's {@code CatalogClient} reuses this decoder through its return types;
 * the six consumed fields are the contract and are asserted field by field.
 */
class FeignConfigTest {

    private final Decoder decoder = new FeignConfig().feignDecoder();

    @Test
    void decode_whenCatalogBodyCarriesUnknownAddedFields_mapsConsumerSubsetWithoutFailing() throws IOException {
        String wireBody = """
                { "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "title": "Clean Code",
                  "author": "Robert C. Martin",
                  "isbn": "9780132350884",
                  "price": 31.99,
                  "coverUrl": "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg",
                  "availability": "IN_STOCK",
                  "stockQuantity": 12,
                  "category": { "id": "00000000-0000-0000-0000-000000000002", "name": "Technology" },
                  "publisherOfTheFuture": "some field cart has never heard of" }
                """;

        BookDto book = (BookDto) decoder.decode(responseWithBody(wireBody), BookDto.class);

        assertThat(book).isNotNull();
        assertThat(book.id()).isEqualTo(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"));
        assertThat(book.title()).isEqualTo("Clean Code");
        assertThat(book.author()).isEqualTo("Robert C. Martin");
        assertThat(book.coverUrl()).isEqualTo("https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg");
        // Money stays BigDecimal across the hop — never widened to double (D-08, NFR-07).
        assertThat(book.price()).isEqualByComparingTo("31.99");
        assertThat(book.stockQuantity()).isEqualTo(12);
    }

    private static Response responseWithBody(String body) {
        Request request = Request.create(Request.HttpMethod.GET,
                "http://catalog-service:8082/api/v1/books/3fa85f64-5717-4562-b3fc-2c963f66afa6",
                jsonHeaders(), Request.Body.empty(), null);
        return Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(jsonHeaders())
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    // The real BookController answers application/json; the converter negotiation in
    // SpringDecoder keys off that header, so the fixture has to carry it too.
    private static Map<String, Collection<String>> jsonHeaders() {
        return Map.of("Content-Type", List.of("application/json"));
    }
}
