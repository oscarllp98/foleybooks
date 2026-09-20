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
 * Public logout endpoint (FR-05, AU-18). Thin by constitutional mandate (C8):
 * Bean Validation at the boundary, one delegation to {@link TokenService},
 * then 204 No Content. The path sits on the SecurityConfig public allowlist
 * (C22, ADR-007) because "public" here means no <em>access</em> token is
 * required — the refresh token in the body is the session credential being
 * ended, and logging out must work even after that access token has expired
 * (D-05, LC-21). Every well-formed request answers the same idempotent 204:
 * a live session's row is deleted, an unknown or already-gone value deletes
 * nothing and still succeeds (LC-09), so the response can never confirm which
 * sessions exist (NFR-01). Only the current session dies; devices holding
 * other refresh tokens are unaffected (FR-05).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, email verification and sessions (FR-01..FR-05)")
public class LogoutController {

    private final TokenService tokenService;

    public LogoutController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/logout")
    @Operation(summary = "End the current session",
            description = "Deletes the refresh-token row behind the presented value, so further refreshes "
                    + "with it fail; the current access token remains valid until its natural expiry "
                    + "(<= 15 min, FR-05). No access token is required. Idempotent: always 204.")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        tokenService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
