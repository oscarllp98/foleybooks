package com.foleybooks.catalog.config;

import com.foleybooks.catalog.common.ProblemDetailResponder;
import com.foleybooks.testsupport.SecurityProbeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deny-by-default proof (NFR-01, CA-02): the real SecurityConfig runs in-slice,
 * security is never disabled (C25), and every rejection is a ProblemDetail (NFR-06).
 * Runs on the docker profile — the application's default profile is dev (which opens
 * the springdoc surface), so "non-dev" has to be pinned explicitly. The slice is
 * pinned to the probe controllers so the authorization evidence stays stable as real
 * catalog controllers arrive; the real controllers' public reachability is proven in
 * their own @WebMvcTest slices. The probe sits outside the scanned package, so it
 * needs both the {@code controllers} filter and the explicit {@code @Import} — the
 * filter alone would leave every probe path 404. The JwtDecoder is a mock: these
 * tests exercise authorization decisions, not signature validation (which auth-service
 * owns) — with it present the real filter chain, including the resource-server
 * BearerTokenAuthenticationFilter, stays fully active.
 */
@WebMvcTest(controllers = SecurityProbeController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class, SecurityProbeController.class})
@ActiveProfiles("docker")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void catalogGetEndpoints_whenRequestedAnonymously_arePublic() throws Exception {
        for (String path : List.of("/api/v1/books", "/api/v1/books/batch",
                "/api/v1/books/3fa85f64-5717-4562-b3fc-2c963f66afa6", "/api/v1/categories")) {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    void unmappedNestedGetUnderBooks_whenRequestedAnonymously_passesAuthorizationProvingWildcardScope() throws Exception {
        // C22 boundary record (ADR-008): the public allowlist is GET-scoped
        // /api/v1/books/** and /api/v1/categories/**, so a 404 (past the filter
        // chain, no handler) instead of 401/403 proves any nested GET under those
        // trees is public BY PATTERN. A future GET that must not be public — an
        // admin list, stats — requires an explicit SecurityConfig matcher change
        // before its handler ships; this test makes that consequence deliberate.
        mockMvc.perform(get("/api/v1/books/admin/audit-trail"))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorHealthInfo_whenRequestedAnonymously_arePublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void writeRequest_whenRequestedAnonymously_deniesWithUnauthorizedProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/books"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/books"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void writeRequest_whenRequestedWithCustomerJwt_deniesWithForbiddenProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/books").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void unlistedPath_whenRequestedWithCustomerJwt_deniesWithForbiddenProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/admin/imports").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:forbidden"))
                .andExpect(jsonPath("$.instance").value("/api/v1/admin/imports"));
    }

    @Test
    void metrics_whenRequestedAnonymously_deniesWithUnauthorized() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"));
    }

    @Test
    void metrics_whenRequestedWithCustomerJwt_deniesWithForbidden() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:forbidden"));
    }

    @Test
    void metrics_whenRequestedWithAdminJwt_passesAuthorization() throws Exception {
        // 404 (no actuator endpoints in this slice) instead of 401/403 proves the
        // ROLE_ADMIN matcher lets admins through (AGENTS.md §5).
        mockMvc.perform(get("/actuator/metrics").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidBearerToken_whenRequestedOnPublicGet_stillDeniesWithUnauthorizedProblemDetail() throws Exception {
        // NimbusJwtDecoder's contract is to throw InvalidBearerTokenException for a
        // token it cannot verify — the mocked decoder mimics that for the presented
        // token. The resource-server filter rejects before authorization ever runs:
        // a public GET with a broken token is a failed authentication, not an
        // anonymous request (fail-closed), rendered as ProblemDetail per ADR-007.
        given(jwtDecoder.decode(anyString())).willThrow(new InvalidBearerTokenException("signature did not verify"));

        mockMvc.perform(get("/api/v1/books").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void swaggerUi_whenNonDevProfile_deniesWithUnauthorized() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isUnauthorized());
    }
}
