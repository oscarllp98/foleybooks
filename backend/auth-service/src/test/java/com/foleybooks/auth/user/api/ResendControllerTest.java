package com.foleybooks.auth.user.api;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Wire contract of POST /api/v1/auth/resend (FR-02, plan §6.2): the real
 * SecurityConfig runs in-slice — security is never disabled (C25) — so the
 * anonymous 202/400 answers below also prove the endpoint is genuinely public
 * (C22) without any auth post-processor: an unverified user holding no access
 * token is exactly who calls this. The service is mocked here; its
 * enumeration-safe branch logic is proven in {@code UserServiceImplTest}.
 */
@WebMvcTest(ResendController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class})
class ResendControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void resend_whenValidEmailAnonymously_returns202GenericEnvelope() throws Exception {
        when(userService.resend(any())).thenReturn(ResendResponse.generic());

        mockMvc.perform(post("/api/v1/auth/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "reader@example.com"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(
                        "If this address has a pending confirmation, a new link is on its way. "
                                + "If you do not see it, please try again in a minute."));

        verify(userService).resend(new ResendRequest("reader@example.com"));
    }

    @Test
    void resend_whenEmailPaddedAndMixedCase_normalizesBeforeDelegating() throws Exception {
        when(userService.resend(any())).thenReturn(ResendResponse.generic());

        mockMvc.perform(post("/api/v1/auth/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "  Reader@Example.COM  "}
                                """))
                .andExpect(status().isAccepted());

        // The boundary hands the canonical address to the service, matching how
        // registration stored it (LC-15, LC-26).
        verify(userService).resend(new ResendRequest("reader@example.com"));
    }

    @Test
    void resend_whenEmailBlank_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));

        verifyNoInteractions(userService);
    }

    @Test
    void resend_whenEmailMalformed_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be a well-formed email address"));

        verifyNoInteractions(userService);
    }
}
