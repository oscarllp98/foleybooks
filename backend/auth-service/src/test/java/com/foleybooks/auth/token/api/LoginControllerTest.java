package com.foleybooks.auth.token.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foleybooks.auth.common.ProblemDetailResponder;
import com.foleybooks.auth.config.SecurityConfig;
import com.foleybooks.auth.token.service.EmailNotVerifiedException;
import com.foleybooks.auth.token.service.InvalidCredentialsException;
import com.foleybooks.auth.token.service.TokenService;
import com.foleybooks.auth.user.domain.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wire contract of POST /api/v1/auth/login (FR-03, plan §6.2): the real
 * SecurityConfig runs in-slice — security is never disabled (C25) — so the
 * anonymous 200 below also proves the endpoint's spot on the public
 * allowlist (C22). The credential rules themselves (normalization, verbatim
 * matching, branch indistinguishability) are proven in
 * {@code TokenServiceImplTest}; ProblemDetail rendering in
 * {@code GlobalExceptionHandlerMockMvcTest}.
 */
@WebMvcTest(LoginController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class})
class LoginControllerTest {

    private static final UUID USER_ID = UUID.fromString("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a");
    private static final String EMAIL = "reader@example.com";
    private static final String PASSWORD = "Bookworm7";
    private static final String RAW_TOKEN = "cafebabe".repeat(8);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    private TokenPair tokenPair() {
        return new TokenPair("eyJhbGciOiJSUzI1NiIs...", RAW_TOKEN, 900,
                new TokenPair.UserSummary(USER_ID, EMAIL, UserRole.CUSTOMER));
    }

    @Test
    void login_whenCredentialsValid_responds200TokenPairAnonymously() throws Exception {
        when(tokenService.login(any(LoginRequest.class))).thenReturn(tokenPair());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "  Reader@Example.COM  ", "password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").value("eyJhbGciOiJSUzI1NiIs..."))
                .andExpect(jsonPath("$.refreshToken").value(RAW_TOKEN))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"));

        // The boundary hands the email over already normalized (LC-24) and
        // the password untouched, spaces and all — trimming is forbidden
        // (LC-26); hashing and matching are the service's job (C24).
        verify(tokenService).login(new LoginRequest(EMAIL, PASSWORD));
    }

    @Test
    void login_whenPaddedPassword_isHandedToServiceVerbatim() throws Exception {
        when(tokenService.login(any(LoginRequest.class))).thenReturn(tokenPair());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": " %s "}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk());

        verify(tokenService).login(new LoginRequest(EMAIL, " " + PASSWORD + " "));
    }

    @Test
    void login_whenCredentialsRejected_responds401GenericProblemDetail() throws Exception {
        // Unknown email and wrong password answer the very same body (FR-03,
        // LC-06) — nothing here may say which branch fired.
        when(tokenService.login(any(LoginRequest.class))).thenThrow(InvalidCredentialsException.generic());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "wrongpassword1"}
                                """.formatted(EMAIL)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:invalid-credentials"))
                .andExpect(jsonPath("$.title").value("Invalid email or password"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("The email or password is incorrect."))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void login_whenAccountUnverified_responds403ConfirmYourEmailProblemDetail() throws Exception {
        // LC-05: the message a legitimate user needs, reached only past a
        // verified password — the service owns that ordering.
        when(tokenService.login(any(LoginRequest.class))).thenThrow(EmailNotVerifiedException.pendingConfirmation());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:email-not-verified"))
                .andExpect(jsonPath("$.title").value("Email not verified"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(
                        "Your email address is not confirmed yet. Check your inbox for the confirmation "
                                + "link, or request a new one."))
                // FR-02's ProblemDetail vocabulary (plan §2): the recovery hint
                // rides its own property, the detail stays user-facing.
                .andExpect(jsonPath("$.resendHint").value(
                        "Request a new link with POST /api/v1/auth/resend."))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void login_whenEmailBlank_responds400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "", "password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));

        verifyNoInteractions(tokenService);
    }

    @Test
    void login_whenPasswordTooLong_responds400WithByteCapError() throws Exception {
        // BCrypt verifies at most 72 bytes; absurd input goes to 400 at the
        // boundary identically for every address, never to a hash comparison.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(EMAIL, "a".repeat(73))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("must be at most 72 bytes"));

        verifyNoInteractions(tokenService);
    }
}
