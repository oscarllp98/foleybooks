package com.foleybooks.auth.user.api;

import com.foleybooks.auth.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public registration endpoint (FR-01). Thin by constitutional mandate (C8):
 * Bean Validation at the boundary, one delegation to {@link UserService},
 * and the fixed 201 — the response is deliberately identical for every
 * branch so nothing here leaks account existence (NFR-01, LC-01). The path
 * sits on the SecurityConfig public allowlist (C22); the gateway forwards
 * {@code /api/v1/auth/**} unmapped to this full route.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, email verification and sessions (FR-01..FR-05)")
public class RegisterController {

    private final UserService userService;

    public RegisterController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register an account",
            description = "Creates an UNVERIFIED account and dispatches a confirmation email. "
                    + "Duplicate emails get the same 201 response without creating anything.")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }
}
