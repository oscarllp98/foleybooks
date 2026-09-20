package com.foleybooks.auth.user.api;

import com.foleybooks.auth.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public email-confirmation endpoint (FR-02). Thin by constitutional mandate
 * (C8): Bean Validation at the boundary, one delegation to
 * {@link UserService}. The path sits on the SecurityConfig public allowlist
 * (C22) because the visitor holding the link has no access token yet —
 * verification is what unlocks login. A live link and a spent one on an
 * already-confirmed account both answer 200 (LC-03, LC-23); unknown,
 * superseded and past-24-hour links answer 410 with a resend hint (LC-02),
 * all rendered by the shared ProblemDetail advice (D-15).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, email verification and sessions (FR-01..FR-05)")
public class ConfirmController {

    private final UserService userService;

    public ConfirmController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm an email address",
            description = "Consumes the token from the confirmation link and marks the account VERIFIED. "
                    + "Re-opening a spent link is an idempotent success; invalid or expired links return 410.")
    public ResponseEntity<ConfirmResponse> confirm(@Valid @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok(userService.confirm(request));
    }
}
