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
 * Public session-refresh endpoint (FR-04, AU-16). Thin by constitutional
 * mandate (C8): Bean Validation at the boundary, one delegation to
 * {@link TokenService}. The path sits on the SecurityConfig public allowlist
 * (C22, ADR-007) because "public" here means no <em>access</em> token is
 * required — the refresh token in the body is the session credential doing
 * the authenticating. A live session rotates into a fresh pair (200); a
 * spent, expired, revoked or unknown value answers the single generic 401,
 * with reuse additionally revoking every session of the account behind it
 * (LC-08) — all rendered through the shared ProblemDetail advice (D-15).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, email verification and sessions (FR-01..FR-05)")
public class RefreshController {

    private final TokenService tokenService;

    public RefreshController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh a session (rotating token pair)",
            description = "Exchanges the current refresh token for a fresh access/refresh pair with a "
                    + "sliding 7-day window. The presented token is single-use: reusing it revokes the "
                    + "account's sessions. Invalid or expired tokens return 401.")
    public ResponseEntity<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(tokenService.refresh(request.refreshToken()));
    }
}
