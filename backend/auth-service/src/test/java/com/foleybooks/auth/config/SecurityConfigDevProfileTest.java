package com.foleybooks.auth.config;

import com.foleybooks.auth.common.ProblemDetailResponder;
import com.foleybooks.testsupport.SecurityProbeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The springdoc surface is dev-only (AGENTS.md §6): the dev profile activates
 * {@code springdoc.api-docs.enabled=true}, and only then SecurityConfig permits it.
 */
@WebMvcTest
@Import({SecurityConfig.class, ProblemDetailResponder.class, SecurityProbeController.class})
@ActiveProfiles("dev")
class SecurityConfigDevProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void swaggerSurface_whenDevProfile_isPublic() throws Exception {
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void metrics_whenDevProfile_stillDeniedAnonymously() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
