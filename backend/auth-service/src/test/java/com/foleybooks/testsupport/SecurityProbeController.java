package com.foleybooks.testsupport;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller mapping the paths SecurityConfig authorizes, so the tests can
 * distinguish "denied by the filter chain" (401/403) from "passed authorization"
 * (200 on a mapped probe, 404 when the path is intentionally unmapped). Lives outside
 * the com.foleybooks.auth package on purpose: it is never component-scanned, so
 * full-context boot tests and future real controllers stay free of ambiguous mappings,
 * and slice tests register it via an explicit @Import.
 */
@RestController
public class SecurityProbeController {

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<Void> register() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/auth/confirm")
    public ResponseEntity<Void> confirm() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/auth/resend")
    public ResponseEntity<Void> resend() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<Void> login() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<Void> refresh() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/oauth2/jwks")
    public ResponseEntity<Void> jwks() {
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
