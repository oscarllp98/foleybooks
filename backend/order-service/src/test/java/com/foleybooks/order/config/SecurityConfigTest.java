package com.foleybooks.order.config;

import com.foleybooks.order.common.ProblemDetailResponder;
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
 * Deny-by-default proof (NFR-01, OR-03, LC-27): the real SecurityConfig runs in-slice,
 * security is never disabled (C25), and every rejection is a ProblemDetail (NFR-06).
 * Runs on the docker profile — the application's default profile is dev (which opens
 * the springdoc surface), so "non-dev" has to be pinned explicitly. The slice is
 * pinned to the probe controllers so the authorization evidence stays stable when the
 * real CartController (OR-06..OR-09) joins the classpath; the probe sits outside the
 * scanned package, so it needs both the {@code controllers} filter and the explicit
 * {@code @Import} — the filter alone would leave every probe path 404. The JwtDecoder
 * is a mock: these tests exercise authorization decisions, not signature validation
 * (which auth-service owns) — with it present the real filter chain, including the
 * resource-server BearerTokenAuthenticationFilter, stays fully active.
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
    void cartGet_whenRequestedAnonymously_deniesWithUnauthorizedProblemDetail() throws Exception {
        // LC-27: the cart guard answer is a 401 ProblemDetail rendered by the chain,
        // never a login redirect or a controller-visible error.
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/cart"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void cartGet_whenRequestedWithCustomerJwt_isAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/cart").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk());
    }

    @Test
    void cartItemPost_whenRequestedWithCustomerJwt_isAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk());
    }

    @Test
    void cartItemPost_whenRequestedAnonymously_deniesWithUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:unauthenticated"));
    }

    @Test
    void unmappedNestedCartPath_whenRequestedWithCustomerJwt_passesAuthorizationProvingWildcardScope() throws Exception {
        // C22 boundary record: the whole /api/v1/cart/** tree is authenticated-only,
        // so a 404 (past the filter chain, no handler) instead of 401/403 proves any
        // nested path under it passes authorization BY PATTERN. Nothing outside the
        // cart tree can ever be public in this service.
        mockMvc.perform(get("/api/v1/cart/nope").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonCartPath_whenRequestedAnonymously_deniesWithUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonCartPath_whenRequestedWithCustomerJwt_deniesWithForbiddenProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/books").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.instance").value("/api/v1/books"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void methodSecurity_whenRequestedWithAdminJwtOnCustomerOnlyCartOperation_deniesWithForbidden() throws Exception {
        // @EnableMethodSecurity proof (D-14): the filter chain lets any authenticated
        // caller into the cart tree, only the @PreAuthorize annotation separates
        // CUSTOMER from ADMIN here — a 403 rendered by the advice's AccessDenied
        // handler is only possible if method security is live.
        mockMvc.perform(get("/api/v1/cart/probe-customer-only")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:foley-books:problem:forbidden"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{8}")));
    }

    @Test
    void methodSecurity_whenRequestedWithCustomerJwtOnCustomerOnlyCartOperation_isAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/cart/probe-customer-only")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealthInfo_whenRequestedAnonymously_arePublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
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
    void invalidBearerToken_whenRequestedOnCartPath_stillDeniesWithUnauthorizedProblemDetail() throws Exception {
        // NimbusJwtDecoder's contract is to throw InvalidBearerTokenException for a
        // token it cannot verify — the mocked decoder mimics that for the presented
        // token. The resource-server filter rejects before authorization ever runs:
        // a broken token is a failed authentication, not an anonymous request
        // (fail-closed), rendered as ProblemDetail per ADR-007.
        given(jwtDecoder.decode(anyString())).willThrow(new InvalidBearerTokenException("signature did not verify"));

        mockMvc.perform(get("/api/v1/cart").header("Authorization", "Bearer not-a-jwt"))
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
