package com.foleybooks.auth.user.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.foleybooks.auth.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wire contract of POST /api/v1/auth/register (FR-01, plan §6.2): the real
 * SecurityConfig runs in-slice — security is never disabled (C25) — so the
 * 201/400 answers below also prove the endpoint is genuinely public (C22)
 * without any auth post-processor. The service is mocked here; its behavior
 * is proven in {@code UserServiceImplTest}, and the ProblemDetail machinery
 * itself in {@code GlobalExceptionHandlerMockMvcTest}.
 */
@WebMvcTest(RegisterController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class})
class RegisterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void register_whenValidPayloadRespondsAnonymously_returns201UnverifiedEnvelope() throws Exception {
        when(userService.register(any()))
                .thenReturn(RegisterResponse.unverified("reader@example.com"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "Reader@Example.com", "password": "Bookworm7"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.email").value("reader@example.com"))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.message")
                        .value("Confirmation is on its way. Check your inbox (and spam folder)."));

        // The boundary has already normalized the address (LC-15, LC-26) — the
        // service is handed the canonical value, never the raw padding/casing.
        verify(userService).register(new RegisterRequest("reader@example.com", "Bookworm7"));
    }

    @Test
    void register_whenEmailPaddedAndMixedCase_returns201WithNormalizedRequest() throws Exception {
        when(userService.register(any()))
                .thenReturn(RegisterResponse.unverified("reader@example.com"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "  Reader@Example.com  ", "password": "Bookworm7"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("reader@example.com"));

        ArgumentCaptor<RegisterRequest> handed = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(userService).register(handed.capture());
        assertThat(handed.getValue()).isEqualTo(new RegisterRequest("reader@example.com", "Bookworm7"));
    }

    @Test
    void register_whenPasswordExceeds72Bytes_returns400WithByteCapacityError() throws Exception {
        // BCrypt hashes at most 72 bytes; the codec would throw past them
        // (latent 500). @MaxUtf8Bytes turns it into the contracted 400 (C23).
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "reader@example.com", "password": "%s"}
                                """.formatted("Aa" + "x".repeat(80))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("must be at most 72 bytes"));

        verifyNoInteractions(userService);
    }

    @Test
    void register_whenPasswordWithoutDigit_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "reader@example.com", "password": "bookworm"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must contain at least one letter and one digit"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));

        verifyNoInteractions(userService);
    }

    @Test
    void register_whenPasswordShorterThanEight_returns400WithLengthError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "reader@example.com", "password": "book7"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("must be at least 8 characters"));

        verifyNoInteractions(userService);
    }

    @Test
    void register_whenEmailMalformed_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "Bookworm7"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be a well-formed email address"));

        verifyNoInteractions(userService);
    }

    @Test
    void register_whenEmailEmpty_returns400WithNotBlankError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "", "password": "Bookworm7"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));

        verifyNoInteractions(userService);
    }
}
