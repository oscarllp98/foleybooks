package com.foleybooks.auth.token.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foleybooks.auth.common.ProblemDetailResponder;
import com.foleybooks.auth.config.SecurityConfig;
import com.foleybooks.auth.token.service.RefreshTokenException;
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
 * Wire contract of POST /api/v1/auth/refresh (FR-04, plan §6.2): the real
 * SecurityConfig runs in-slice — security is never disabled (C25) — so the
 * anonymous 200/401 below also prove the endpoint's spot on the public
 * allowlist (C22, ADR-007: the refresh token in the body is the credential).
 * Rotation and revocation logic is mocked here and proven in
 * {@code TokenServiceImplTest}; ProblemDetail rendering in
 * {@code GlobalExceptionHandlerMockMvcTest}.
 */
@WebMvcTest(RefreshController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class})
class RefreshControllerTest {

    private static final UUID USER_ID = UUID.fromString("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a");
    private static final String RAW_TOKEN = "cafebabe".repeat(8);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    private TokenPair tokenPair() {
        return new TokenPair("eyJhbGciOiJSUzI1NiIs...", RAW_TOKEN + "_next", 900,
                new TokenPair.UserSummary(USER_ID, "reader@example.com", UserRole.CUSTOMER));
    }

    @Test
    void refresh_whenSessionRotates_responds200TokenPairAnonymously() throws Exception {
        when(tokenService.refresh(RAW_TOKEN)).thenReturn(tokenPair());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(RAW_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").value("eyJhbGciOiJSUzI1NiIs..."))
                .andExpect(jsonPath("$.refreshToken").value(RAW_TOKEN + "_next"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.user.email").value("reader@example.com"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"));

        // The boundary hands the raw value over untouched — hashing is the
        // service's job, never the transport layer's (C24).
        verify(tokenService).refresh(RAW_TOKEN);
    }

    @Test
    void refresh_whenTokenRejected_responds401GenericProblemDetail() throws Exception {
        // Unknown, expired, spent (reuse) and revoked all answer the very same
        // body (FR-04) — nothing here may say which branch fired.
        when(tokenService.refresh(RAW_TOKEN)).thenThrow(RefreshTokenException.invalidOrExpired());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(RAW_TOKEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:invalid-or-expired-refresh-token"))
                .andExpect(jsonPath("$.title").value("Session is no longer valid"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("This session is no longer valid. Please log in again."))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/refresh"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void refresh_whenTokenBlank_responds400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("refreshToken"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));

        verifyNoInteractions(tokenService);
    }

    @Test
    void refresh_whenTokenOversized_responds400WithLengthError() throws Exception {
        // Raw tokens are 64 hex chars; garbage goes to 400 at the boundary,
        // not to a hash lookup (C23).
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted("a".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("refreshToken"))
                .andExpect(jsonPath("$.errors[0].message").value("must be at most 64 characters"));

        verifyNoInteractions(tokenService);
    }
}
