package com.foleybooks.auth.config;

import com.foleybooks.auth.common.ProblemDetailResponder;
import com.foleybooks.testsupport.SecurityProbeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deny-by-default proof (NFR-01, AU-04): the real SecurityConfig runs in-slice,
 * security is never disabled (C25), and every rejection is a ProblemDetail (NFR-06).
 * Runs on the docker profile — the application's default profile is dev (which opens
 * the springdoc surface), so "non-dev" has to be pinned explicitly. The slice is
 * pinned to the probe controllers so the authorization evidence stays stable as real
 * auth controllers arrive (a real register handler would answer 400 to the probe's
 * bodiless POST and collide with the probe mapping); the real controllers' public
 * reachability is proven in their own @WebMvcTest slices. The probe sits outside
 * the scanned package, so it needs both the {@code controllers} filter and the
 * explicit {@code @Import} — the filter alone would leave every probe path 404.
 */
@WebMvcTest(controllers = SecurityProbeController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class, SecurityProbeController.class})
@ActiveProfiles("docker")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void authEndpoints_whenRequestedAnonymously_arePublic() throws Exception {
        for (String path : List.of("/api/v1/auth/register", "/api/v1/auth/confirm", "/api/v1/auth/resend",
                "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")) {
            mockMvc.perform(post(path)).andExpect(status().isOk());
        }
    }

    @Test
    void jwksAndActuatorHealthInfo_whenRequestedAnonymously_arePublic() throws Exception {
        mockMvc.perform(get("/oauth2/jwks")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void unlistedPath_whenRequestedAnonymously_deniesWithUnauthorizedProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/orders"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void unlistedPath_whenRequestedAsCustomer_deniesWithForbiddenProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/orders").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void metrics_whenRequestedAnonymously_deniesWithUnauthorized() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"));
    }

    @Test
    void metrics_whenRequestedAsCustomer_deniesWithForbidden() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:forbidden"));
    }

    @Test
    void metrics_whenRequestedAsAdmin_passesAuthorization() throws Exception {
        // 404 (no actuator endpoints in this slice) instead of 401/403 proves the
        // ROLE_ADMIN matcher lets admins through: fail-closed for everyone else (ADR-007).
        mockMvc.perform(get("/actuator/metrics").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void swaggerUi_whenNonDevProfile_deniesWithUnauthorized() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isUnauthorized());
    }
}
