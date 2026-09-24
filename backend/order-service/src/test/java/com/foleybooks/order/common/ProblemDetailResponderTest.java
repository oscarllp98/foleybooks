package com.foleybooks.order.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailResponderTest {

    /**
     * A Spring-configured mapper (like Boot injects in production): it registers the
     * ProblemDetail support that flattens extra properties (traceId) to the top level.
     */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final ProblemDetailResponder responder = new ProblemDetailResponder(objectMapper);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/cart/items");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void commence_whenAnonymous_deniesWith401ProblemDetailAndTraceId() throws Exception {
        MDC.put(TraceIdFilter.MDC_KEY, "b1b2c3d4");

        responder.commence(request, response, new InsufficientAuthenticationException("Full authentication required"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(MediaType.parseMediaType(response.getContentType())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("type").asText()).isEqualTo("urn:foley-books:problem:unauthenticated");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(body.get("instance").asText()).isEqualTo("/api/v1/cart/items");
        assertThat(body.get("traceId").asText()).isEqualTo("b1b2c3d4");
    }

    @Test
    void commence_whenBearerTokenInvalid_deniesWith401ProblemDetailAndTraceId() throws Exception {
        // The resource-server path (ADR-007): the token failed validation upstream.
        // The message must never leak into the response (C24).
        responder.commence(request, response, new InvalidBearerTokenException("junk token hunter2"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("type").asText()).isEqualTo("urn:foley-books:problem:unauthenticated");
        assertThat(response.getContentAsString()).doesNotContain("hunter2");
    }

    @Test
    void handle_whenAuthorizedUserForbidden_deniesWith403ForbiddenProblem() throws Exception {
        responder.handle(request, response, new AccessDeniedException("user lacks ROLE_ADMIN"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("type").asText()).isEqualTo("urn:foley-books:problem:forbidden");
        assertThat(body.get("detail").asText()).isEqualTo("You are not allowed to perform this action.");
        assertThat(body.get("traceId").asText()).matches("[0-9a-f]{8}");
    }
}
