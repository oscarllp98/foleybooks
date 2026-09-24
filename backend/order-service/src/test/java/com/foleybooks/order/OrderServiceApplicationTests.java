package com.foleybooks.order;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Scaffold boot proof (OR-03): the full context starts on a real PostgreSQL — datasource,
 * JPA validate, resource-server security, Feign and the Feign-aware advice all wire
 * together. Cart endpoints don't exist yet (OR-06..OR-10), so the LC-27 anonymous-cart 401
 * is asserted here through the live filter chain rather than via a controller. The empty
 * migration set is OR-04's to fill; {@code @DataJpaTest} then pins migration application.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "EUREKA_USERNAME=test-user",
        "EUREKA_PASSWORD=test-secret",
        "ORDER_DB_PASSWORD=test-db-secret"
})
class OrderServiceApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
        // OR-03 deny-by-default smoke on the real chain: order is a resource server,
        // so unauthenticated /actuator/metrics is 401 now, not only fail-closed in theory.
        var response = restTemplate.getForEntity("/actuator/metrics", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void cart_whenRequestedAnonymously_deniesWithUnauthorizedProblemDetail() {
        // LC-27 end-to-end on the booted chain: anonymous cart traffic is a ProblemDetail
        // 401, before any controller exists to (mis)handle it.
        var response = restTemplate.getForEntity("/api/v1/cart", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(contentType -> assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .isTrue());
        assertThat(response.getBody()).contains("urn:foley-books:problem:unauthenticated");
    }
}
