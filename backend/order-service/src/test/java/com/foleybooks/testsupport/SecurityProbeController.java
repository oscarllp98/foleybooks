package com.foleybooks.testsupport;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller mapping the paths SecurityConfig authorizes, so the tests can
 * distinguish "denied by the filter chain" (401/403) from "passed authorization"
 * (200 on a mapped probe, 404 when the path is intentionally unmapped). Lives outside
 * the com.foleybooks.order package on purpose: it is never component-scanned, so
 * full-context boot tests and the future real CartController stay free of ambiguous
 * mappings, and slice tests register it via an explicit @Import.
 */
@RestController
public class SecurityProbeController {

    // Mapped with the same guard shape the cart operations of OR-06..OR-09 will ship:
    // the chain already requires authentication, the annotation is the cart's own rule.
    @GetMapping("/api/v1/cart")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> cart() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/cart/items")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> addItem() {
        return ResponseEntity.ok().build();
    }

    /**
     * Method-security proof: a role rule the filter chain deliberately does not express
     * (the cart tree is authenticated-only for every role). An ADMIN token passes the
     * chain and is then rejected by the annotation — only possible if
     * {@code @EnableMethodSecurity} is active — while a CUSTOMER token passes both.
     */
    @GetMapping("/api/v1/cart/probe-customer-only")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<Void> customerOnly() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/actuator/health")
    public ResponseEntity<Void> health() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/actuator/health/liveness")
    public ResponseEntity<Void> liveness() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/actuator/info")
    public ResponseEntity<Void> info() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/swagger-ui.html")
    public ResponseEntity<Void> swaggerUi() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/v3/api-docs")
    public ResponseEntity<Void> apiDocs() {
        return ResponseEntity.ok().build();
    }
}
