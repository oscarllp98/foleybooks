package com.foleybooks.order.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Renders security-filter rejections (the anonymous cart 401, non-cart denials, fail-closed
 * metrics) with the same ProblemDetail contract as the MVC advice (AGENTS.md §6, NFR-06,
 * ADR-007): 401 {@code urn:foley-books:problem:unauthenticated} from the entry point and
 * 403 {@code urn:foley-books:problem:forbidden} from the denied handler, each carrying
 * {@code instance} and a {@code traceId} that correlates with the server logs. In
 * order-service — a resource server like catalog (ADR-008) — these rejections are real
 * traffic, and LC-27's anonymous-cart 401 is rendered here, not by the controllers.
 */
@Component
public class ProblemDetailResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailResponder.class);

    private final ObjectMapper objectMapper;

    public ProblemDetailResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthorized",
                "Authentication is required to access this resource.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "You are not allowed to perform this action.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String type, String title, String detail) throws IOException {
        String traceId = TraceIdFilter.currentTraceId();
        // Only route + trace id are logged: exception messages could carry credentials (C24).
        log.debug("{} {} denied as {} [{}]", request.getMethod(), request.getRequestURI(), type, traceId);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(ApiException.TYPE_URN_PREFIX + type));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("traceId", traceId);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
