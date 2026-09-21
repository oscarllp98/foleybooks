package com.foleybooks.testsupport;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller mapping the paths SecurityConfig authorizes, so the tests can
 * distinguish "denied by the filter chain" (401/403) from "passed authorization"
 * (200 on a mapped probe, 404 when the path is intentionally unmapped). Lives outside
 * the com.foleybooks.catalog package on purpose: it is never component-scanned, so
 * full-context boot tests and future real controllers stay free of ambiguous mappings,
 * and slice tests register it via an explicit @Import.
 */
@RestController
public class SecurityProbeController {

    @GetMapping("/api/v1/books")
    public ResponseEntity<Void> books() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/books/batch")
    public ResponseEntity<Void> booksBatch() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/books/{id}")
    public ResponseEntity<Void> book(@PathVariable String id) {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/categories")
    public ResponseEntity<Void> categories() {
        return ResponseEntity.ok().build();
    }

    // Mapped so a denial on a write method proves the filter chain (401/403) and not a
    // missing handler (404). The MVP catalog has no write endpoints — these exist only
    // to give the SecurityConfig probe something to deny.
    @PostMapping("/api/v1/books")
    public ResponseEntity<Void> createBook() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/categories")
    public ResponseEntity<Void> createCategory() {
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
