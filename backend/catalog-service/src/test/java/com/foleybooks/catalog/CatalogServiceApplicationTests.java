package com.foleybooks.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.foleybooks.catalog.book.api.Availability;
import com.foleybooks.catalog.book.api.BookResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "EUREKA_USERNAME=test-user",
        "EUREKA_PASSWORD=test-secret",
        "CATALOG_DB_PASSWORD=test-db-secret"
})
class CatalogServiceApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** The V2 seed row (CA-04) the FR-07 detail contract is pinned against. */
    private static final UUID CLEAN_CODE_ID = UUID.fromString("00000000-0000-0000-0000-00000000cb06");

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void startup_whenPostgresAvailable_bootsSuccessfully() {
    }

    @Test
    void health_whenRequestedAnonymously_isPublic() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void metrics_whenRequestedAnonymously_deniesWithUnauthorized() {
        // CA-02 deny-by-default smoke on the real chain: catalog is a resource server,
        // so unauthenticated /actuator/metrics is 401 now, not only fail-closed in theory.
        var response = restTemplate.getForEntity("/actuator/metrics", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bookDetail_whenRequestedForASeedBook_resolvesTheLazyCategoryOnAPublicRead() {
        // CA-09 / FR-07 through the whole stack, which no slice test can reach: the
        // service is real here, {@code open-in-view} is off (application.yml) and
        // {@code category} is a lazy proxy, so only a live request proves the
        // projection happens inside the read-only transaction — otherwise the
        // detail page of a book that exists would answer 500 instead. The fixture is
        // the V2 seed row itself, so the response also pins the FR-14 catalog a
        // reviewer sees on a fresh startup.
        var response = restTemplate.getForEntity("/api/v1/books/" + CLEAN_CODE_ID, BookResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        BookResponse book = response.getBody();
        assertThat(book.id()).isEqualTo(CLEAN_CODE_ID);
        assertThat(book.title()).isEqualTo("Clean Code");
        assertThat(book.author()).isEqualTo("Robert C. Martin");
        assertThat(book.isbn()).isEqualTo("9780132350884");
        assertThat(book.price()).isEqualByComparingTo("31.99");
        assertThat(book.coverUrl())
                .isEqualTo("https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg");
        assertThat(book.stockQuantity()).isEqualTo(12);
        assertThat(book.availability()).isEqualTo(Availability.IN_STOCK);
        assertThat(book.category()).isNotNull();
        assertThat(book.category().id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-00000000ca02"));
        assertThat(book.category().name()).isEqualTo("Technology");
    }

    @Test
    void bookDetail_whenIdMatchesNoBook_answersNotFoundAsProblemDetail() {
        // FR-07's other half on the real chain: a well-formed id with no row is the
        // declared book-not-found ProblemDetail, rendered by the shipped advice and
        // negotiated by the actual content-negotiation stack.
        UUID missingId = UUID.fromString("00000000-0000-0000-0000-00000000cb99");

        var response = restTemplate.getForEntity("/api/v1/books/" + missingId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(contentType -> assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .isTrue());
        assertThat(response.getBody()).contains("urn:foley-books:problem:book-not-found");
    }
}
