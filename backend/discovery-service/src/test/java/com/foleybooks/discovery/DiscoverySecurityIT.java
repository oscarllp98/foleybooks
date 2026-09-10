package com.foleybooks.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "EUREKA_SECURITY_USERNAME=test-admin",
                "EUREKA_SECURITY_PASSWORD=test-secret"
        })
class DiscoverySecurityIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void getHealth_whenAnonymous_isPermitted() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getInfo_whenAnonymous_isPermitted() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getMetrics_whenAnonymous_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getRegistry_whenAnonymous_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/eureka/apps", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getRegistry_whenAuthenticatedAsAdmin_isOk() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test-admin", "test-secret")
                .getForEntity("/eureka/apps", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getRegistry_whenStandalone_doesNotSelfRegister() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test-admin", "test-secret")
                .getForEntity("/eureka/apps", String.class);

        assertThat(response.getBody()).doesNotContain("DISCOVERY-SERVICE");
    }

    @Test
    void getMetrics_whenAuthenticatedAsAdmin_isOk() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test-admin", "test-secret")
                .getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
