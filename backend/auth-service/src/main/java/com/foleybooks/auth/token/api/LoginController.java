package com.foleybooks.auth.token.api;

import com.foleybooks.auth.token.service.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public login endpoint (FR-03, AU-17). Thin by constitutional mandate (C8):
 * Bean Validation at the boundary — which already normalizes the email so the
 * lookup sees the canonical form (LC-24) — and one delegation to
 * {@link TokenService}. The path sits on the SecurityConfig public allowlist
 * (C22): authenticating is this endpoint's job, it presupposes no access
 * token. Correct credentials for a VERIFIED account open a fresh rotating
 * session and answer 200 with the plan §2 {@link TokenPair}; an unknown
 * address or a wrong password answer the single generic 401 that never says
 * which (LC-06), and a verified password on an UNVERIFIED account answers
 * 403 with the confirm-your-email message (LC-05) — all rendered as
 * ProblemDetail through the shared advice (D-15).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, email verification and sessions (FR-01..FR-05)")
public class LoginController {

    private final TokenService tokenService;

    public LoginController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email and password",
            description = "Exchanges verified credentials for a token pair: a 15-minute access JWT plus a "
                    + "fresh 7-day rotating refresh session. Wrong credentials return a generic 401; an "
                    + "unconfirmed email returns 403 with the confirmation hint.")
    public ResponseEntity<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(tokenService.login(request));
    }
}
