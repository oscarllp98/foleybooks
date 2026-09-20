package com.foleybooks.auth.token.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foleybooks.auth.common.ProblemDetailResponder;
import com.foleybooks.auth.config.SecurityConfig;
import com.foleybooks.auth.token.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wire contract of POST /api/v1/auth/logout (FR-05, plan §6.2): the real
 * SecurityConfig runs in-slice — security is never disabled (C25) — so the
 * anonymous 204s below are also the proof that no access token is required
 * (C22, ADR-007, D-05, LC-21). The idempotence rule (LC-09) is a wire
 * property here: every well-formed call answers 204 regardless of whether a
 * row existed, which the mocked void service makes unfailable.
 */
@WebMvcTest(LogoutController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class})
class LogoutControllerTest {

    private static final String RAW_TOKEN = "cafebabe".repeat(8);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @Test
    void logout_whenSessionLive_responds204WithoutAnyAccessToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(RAW_TOKEN)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // The boundary hands the raw value over untouched — hashing is the
        // service's job, never the transport layer's (C24).
        verify(tokenService).logout(RAW_TOKEN);
    }

    @Test
    void logout_whenTokenUnknown_responds204IdenticallyAsIdempotentSuccess() throws Exception {
        // LC-09: already logged out, spent or never-issued — the service
        // deletes nothing and the wire answer does not change.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted("f".repeat(64))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(tokenService).logout("f".repeat(64));
    }

    @Test
    void logout_whenTokenBlank_responds400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
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
    void logout_whenTokenOversized_responds400WithLengthError() throws Exception {
        // Raw tokens are 64 hex chars; garbage goes to 400 at the boundary,
        // not to a hash delete (C23) — no issued value can be that long.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted("a".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("refreshToken"))
                .andExpect(jsonPath("$.errors[0].message").value("must be at most 64 characters"));

        verifyNoInteractions(tokenService);
    }

    @Test
    void logout_whenBodyMissing_responds400MalformedWithoutTouchingService() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:malformed-request-body"));

        verifyNoInteractions(tokenService);
    }
}
