package com.foleybooks.order.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Binds a short trace id to every request (NFR-06, D-15): reused from {@code X-Trace-Id}
 * when an upstream (the gateway) provided a well-formed one, generated otherwise. The id is
 * published to the MDC so log lines carry it, echoed back in the response header and in
 * every ProblemDetail body.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    static final int TRACE_ID_LENGTH = 8;

    private static final Pattern HEX_ID = Pattern.compile("[0-9a-fA-F]{1,32}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = incomingTraceId(request);
        if (traceId == null) {
            traceId = newTraceId();
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Returns the trace id bound to the current request; generates and binds one when the
     * caller runs outside the filter chain (e.g. tests invoking handlers directly).
     */
    public static String currentTraceId() {
        String traceId = MDC.get(MDC_KEY);
        if (traceId == null) {
            traceId = newTraceId();
            MDC.put(MDC_KEY, traceId);
        }
        return traceId;
    }

    @Nullable
    private static String incomingTraceId(HttpServletRequest request) {
        String header = request.getHeader(TRACE_ID_HEADER);
        if (header == null || !HEX_ID.matcher(header).matches()) {
            return null;
        }
        return header.toLowerCase(Locale.ROOT);
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, TRACE_ID_LENGTH);
    }
}
