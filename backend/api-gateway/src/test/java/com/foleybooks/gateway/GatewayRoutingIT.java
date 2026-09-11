package com.foleybooks.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eureka.client.enabled=false",
                "EUREKA_USERNAME=test-user",
                "EUREKA_PASSWORD=test-secret"
        })
class GatewayRoutingIT {

    @LocalServerPort
    private int port;

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void routes_whenLoaded_matchPlanEndpointMap() {
        List<RouteDefinition> definitions = routeDefinitionLocator.getRouteDefinitions().collectList().block();

        assertThat(definitions).isNotNull();
        assertRoute(definitions, "auth", "lb://auth-service", "/api/v1/auth/**");
        assertRoute(definitions, "catalog-books", "lb://catalog-service", "/api/v1/books/**");
        assertRoute(definitions, "catalog-categories", "lb://catalog-service", "/api/v1/categories/**");
        assertRoute(definitions, "order-cart", "lb://order-service", "/api/v1/cart/**");
    }

    private void assertRoute(List<RouteDefinition> definitions, String id, String uri, String path) {
        RouteDefinition route = definitions.stream()
                .filter(candidate -> candidate.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing route: " + id));
        assertThat(route.getUri()).isEqualTo(URI.create(uri));
        assertThat(route.getPredicates())
                .anySatisfy(predicate -> {
                    assertThat(predicate.getName()).isEqualTo("Path");
                    assertThat(predicate.getArgs().values()).contains(path);
                });
    }

    @Test
    void optionsPreflight_whenOriginIsFrontend_isAnsweredWithCorsHeaders() {
        client().options().uri("/api/v1/books")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,PATCH,DELETE,OPTIONS");
    }

    @Test
    void optionsPreflight_whenOriginIsUnknown_omitsAllowOriginHeader() {
        client().options().uri("/api/v1/books")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void getHealth_whenAnonymous_isPermitted() {
        client().get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void getMetrics_whenExposed_isNotPubliclyAvailable() {
        client().get().uri("/actuator/metrics")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getUnknownPath_whenNoRouteMatches_isNotFound() {
        client().method(HttpMethod.GET).uri("/api/v1/unknown")
                .exchange()
                .expectStatus().isNotFound();
    }
}
