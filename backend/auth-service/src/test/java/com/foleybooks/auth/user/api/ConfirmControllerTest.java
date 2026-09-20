package com.foleybooks.auth.user.api;

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
import com.foleybooks.auth.user.service.ConfirmationTokenException;
import com.foleybooks.auth.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wire contract of POST /api/v1/auth/confirm (FR-02, plan §6.2): the real
 * SecurityConfig runs in-slice — security is never disabled (C25) — so the
 * anonymous 200 below also proves the endpoint is genuinely public (C22)
 * without any auth post-processor. The service is mocked here; its branch
 * logic is proven in {@code UserServiceImplTest} and the ProblemDetail
 * machinery in {@code GlobalExceptionHandlerMockMvcTest}.
 */
@WebMvcTest(ConfirmController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class})
class ConfirmControllerTest {

    private static final String RAW_TOKEN = "cafebabe".repeat(8);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void confirm_whenLinkVerifiesTheAccount_responds200ConfirmedAnonymously() throws Exception {
        when(userService.confirm(any())).thenReturn(ConfirmResponse.confirmed());

        mockMvc.perform(post("/api/v1/auth/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s"}
                                """.formatted(RAW_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"))
                .andExpect(jsonPath("$.message").value("Email confirmed. You can log in now."));

        // The boundary hands the raw value to the service untouched — hashing
        // is the token service's job, never the transport layer's (C24).
        verify(userService).confirm(new ConfirmRequest(RAW_TOKEN));
    }

    @Test
    void confirm_whenAccountAlreadyVerified_responds200IdempotentSuccess() throws Exception {
        // LC-03: a spent link is a success, not an error, and the outcome
        // discriminator lets the UI tell the two apart.
        when(userService.confirm(any())).thenReturn(ConfirmResponse.alreadyConfirmed());

        mockMvc.perform(post("/api/v1/auth/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s"}
                                """.formatted(RAW_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_CONFIRMED"))
                .andExpect(jsonPath("$.message").value("This email was already confirmed. You can log in now."));
    }

    @Test
    void confirm_whenLinkExpired_responds410ProblemDetailWithResendHint() throws Exception {
        when(userService.confirm(any())).thenThrow(ConfirmationTokenException.expired());

        mockMvc.perform(post("/api/v1/auth/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s"}
                                """.formatted(RAW_TOKEN)))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:expired-confirmation-token"))
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/confirm"))
                .andExpect(jsonPath("$.detail").value("This confirmation link has expired. You can request a new one."))
                .andExpect(jsonPath("$.resendHint").value("Request a new link with POST /api/v1/auth/resend."))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void confirm_whenTokenBlank_responds400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("token"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));

        verifyNoInteractions(userService);
    }

    @Test
    void confirm_whenTokenOversized_responds400WithLengthError() throws Exception {
        // Raw tokens are 64 hex chars; anything longer is garbage that must
        // fail at the boundary instead of reaching the hash lookup (C23).
        mockMvc.perform(post("/api/v1/auth/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s"}
                                """.formatted("a".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("token"))
                .andExpect(jsonPath("$.errors[0].message").value("must be at most 64 characters"));

        verifyNoInteractions(userService);
    }
}
