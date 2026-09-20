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
 * Public confirmation-resend endpoint (FR-02, AU-14). Thin by constitutional
 * mandate (C8): Bean Validation at the boundary, one delegation to
 * {@link UserService}, and the fixed 202 — deliberately <em>not</em> 201,
 * because nothing is created the caller can point at, and deliberately the
 * same accepted-with-generic-body answer whether the address exists, is
 * verified, was throttled or received a fresh link, so no response ever
 * confirms or denies an account (LC-20, NFR-01). The path sits on the
 * SecurityConfig public allowlist (C22): unverified users cannot log in, so
 * resend must work without an access token.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, email verification and sessions (FR-01..FR-05)")
public class ResendController {

    private final UserService userService;

    public ResendController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/resend")
    @Operation(summary = "Resend the confirmation email",
            description = "Requests a fresh confirmation link for the account email. Always answers "
                    + "202 with the same generic body, whether the address is unknown, verified or pending.")
    public ResponseEntity<ResendResponse> resend(@Valid @RequestBody ResendRequest request) {
        return ResponseEntity.accepted().body(userService.resend(request));
    }
}
