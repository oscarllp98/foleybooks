package com.foleybooks.order.cart.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.foleybooks.order.cart.service.BookNotFoundException;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.Response;
import feign.optionals.OptionalDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * Pins the ADR-005 transport contract of {@link CatalogClient} without booting
 * Spring: the Eureka service-name target (never a host literal, never the
 * gateway), the gateway-identical {@code /api/v1/books/**} paths, the repeated
 * {@code ids=} batch form the catalog's {@code List<UUID>} binder consumes, the
 * absence of any {@code Authorization} relay (C22, ADR-005), and both decode
 * outcomes — a 200 body into the six-field {@link BookDto} subset and catalog's
 * 404 into {@link BookNotFoundException} through the shared error decoder.
 */
class CatalogClientContractTest {

    private static final UUID BOOK_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-00000000cb06");

    private final RecordingClient recordingClient = new RecordingClient();

    private CatalogClient buildClientWith(Response response) {
        recordingClient.enqueue(response);
        // The same codecs FeignConfig exposes (ADR-005 layering) — rebuilt here
        // because @Bean methods stay package-private, as in every other config
        // class; FeignConfigTest pins their exact configuration, and
        // OrderServiceApplicationTests pins the Spring wiring of the same beans.
        return Feign.builder()
                .client(recordingClient)
                .contract(new SpringMvcContract())
                .decoder(tolerantFeignDecoder())
                .errorDecoder(new CatalogErrorDecoder())
                .target(CatalogClient.class, "http://catalog-service:8082");
    }

    /** Mirrors {@code FeignConfig#feignDecoder()}: FAIL_ON_UNKNOWN_PROPERTIES = false (ADR-005). */
    private static OptionalDecoder tolerantFeignDecoder() {
        ObjectMapper tolerantMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        ObjectFactory<HttpMessageConverters> converters = () ->
                new HttpMessageConverters(new MappingJackson2HttpMessageConverter(tolerantMapper));
        return new OptionalDecoder(new ResponseEntityDecoder(new SpringDecoder(converters)));
    }

    @Test
    void target_whenClientIsDeclared_resolvesTheEurekaServiceNameNotAHostLiteral() {
        FeignClient annotation = CatalogClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("catalog-service");
        // No url() attribute → LoadBalancer resolution over the registry (ADR-005/ADR-006);
        // no path() prefix either, because the declared paths are already absolute.
        assertThat(annotation.url()).isEmpty();
        assertThat(annotation.path()).isEmpty();
    }

    @Test
    void findBook_whenCalled_issuesGatewayIdenticalGetWithoutAuthRelay() {
        CatalogClient client = buildClientWith(response(200, bookJson("Clean Code", BOOK_ID)));

        BookDto book = client.findBook(BOOK_ID);

        RequestSnapshot sent = recordingClient.last();
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.url()).isEqualTo("http://catalog-service:8082/api/v1/books/" + BOOK_ID);
        assertThat(sent.header("Authorization")).isNull();
        assertThat(book.id()).isEqualTo(BOOK_ID);
        assertThat(book.title()).isEqualTo("Clean Code");
        // Money and stock keep their published types across the hop (D-08, NFR-07).
        assertThat(book.price()).isEqualByComparingTo("31.99");
        assertThat(book.stockQuantity()).isEqualTo(12);
    }

    @Test
    void batchBooks_whenCalled_sendsRepeatedIdsParamsAgainstTheBatchRoute() {
        CatalogClient client = buildClientWith(response(200,
                "[" + bookJson("Clean Code", BOOK_ID) + "," + bookJson("Dune", OTHER_ID) + "]"));

        List<BookDto> books = client.batchBooks(List.of(BOOK_ID, OTHER_ID));

        RequestSnapshot sent = recordingClient.last();
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.url()).startsWith("http://catalog-service:8082/api/v1/books/batch");
        // Repeated ids= — the form Spring binds to List<UUID> on the catalog side (ADR-005).
        assertThat(queryValues(sent.url(), "ids")).containsExactly(BOOK_ID.toString(), OTHER_ID.toString());
        assertThat(sent.header("Authorization")).isNull();
        // ADR-009: the batch is a bare array; the consumer must not expect an envelope.
        assertThat(books).extracting(BookDto::id).containsExactly(BOOK_ID, OTHER_ID);
    }

    @Test
    void batchBooks_whenIdListIsEmpty_stillRendersTheBatchRouteWithoutCrashing() {
        // OR-07 may short-circuit an empty cart before the call, but the client must
        // not be the place a zero-id batch blows up.
        CatalogClient client = buildClientWith(response(200, "[]"));

        List<BookDto> books = client.batchBooks(List.of());

        assertThat(recordingClient.last().url()).startsWith("http://catalog-service:8082/api/v1/books/batch");
        assertThat(books).isEmpty();
    }

    @Test
    void findBook_whenCatalogAnswers404WithNotFound_throwsBookNotFoundThroughTheDecoder() {
        CatalogClient client = buildClientWith(response(404, """
                { "type": "urn:foley-books:problem:book-not-found",
                  "title": "Book not found", "status": 404,
                  "detail": "No book exists with the given id.",
                  "bookId": "3fa85f64-5717-4562-b3fc-2c963f66afa6" }
                """));

        assertThatExceptionOfType(BookNotFoundException.class)
                .isThrownBy(() -> client.findBook(BOOK_ID))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void batchBooks_whenCatalogAnswers200WithMissingEntry_decodesTheShortListWithoutError() {
        // LC-14's transport half: an id that names no book is simply absent from the
        // array (CA-11), so the call returns the survivors — no exception, no 404.
        CatalogClient client = buildClientWith(response(200, "[" + bookJson("Dune", OTHER_ID) + "]"));

        List<BookDto> books = client.batchBooks(List.of(BOOK_ID, OTHER_ID));

        assertThat(books).extracting(BookDto::id).containsExactly(OTHER_ID);
    }

    private static String bookJson(String title, UUID id) {
        // The full nine-field ADR-009 wire body — the consumer subset must tolerate it.
        return """
                { "id": "%s", "title": "%s", "author": "Someone", "isbn": "9780132350884",
                  "price": 31.99, "coverUrl": "https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg",
                  "availability": "IN_STOCK", "stockQuantity": 12,
                  "category": { "id": "00000000-0000-0000-0000-00000000ca02", "name": "Technology" } }
                """.formatted(id, title);
    }

    private static Response response(int status, String body) {
        Map<String, Collection<String>> headers = Map.of("Content-Type", List.of("application/json"));
        Request request = Request.create(Request.HttpMethod.GET, "http://catalog-service:8082/api/v1/books",
                headers, Request.Body.empty(), null);
        return Response.builder()
                .status(status)
                .reason("status " + status)
                .request(request)
                .headers(headers)
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    private static List<String> queryValues(String url, String parameter) {
        String query = url.substring(url.indexOf('?') + 1);
        List<String> values = new ArrayList<>();
        for (String pair : query.split("&")) {
            String[] split = pair.split("=", 2);
            if (split.length == 2 && split[0].equals(parameter)) {
                values.add(split[1]);
            }
        }
        return values;
    }

    /** What Feign actually rendered before dispatch — method, URL and headers as sent. */
    private record RequestSnapshot(String method, String url, Map<String, Collection<String>> headers) {
        String header(String name) {
            Collection<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.iterator().next();
        }
    }

    /** Captures every dispatched request and replays scripted responses in order. */
    private static final class RecordingClient implements Client {

        private final List<Response> scripted = new ArrayList<>();
        private final List<RequestSnapshot> sent = new ArrayList<>();

        void enqueue(Response response) {
            scripted.add(response);
        }

        RequestSnapshot last() {
            return sent.get(sent.size() - 1);
        }

        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            sent.add(new RequestSnapshot(request.httpMethod().name(), request.url(), request.headers()));
            if (scripted.isEmpty()) {
                throw new IOException("no scripted response for " + request.url());
            }
            return scripted.remove(0);
        }
    }
}
