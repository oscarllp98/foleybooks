package com.foleybooks.auth.common;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void doFilter_whenNoIncomingHeader_generatesTraceIdInResponseHeaderAndMdc() throws Exception {
        AtomicReference<String> traceIdDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> traceIdDuringChain.set(MDC.get(TraceIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        String traceId = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(traceId).matches("[0-9a-f]{8}");
        assertThat(traceIdDuringChain.get()).isEqualTo(traceId);
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void doFilter_whenHeaderFromUpstreamIsValidHex_reusesItInLowerCase() throws Exception {
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "ABCDEF01");
        AtomicReference<String> traceIdDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> traceIdDuringChain.set(MDC.get(TraceIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(traceIdDuringChain.get()).isEqualTo("abcdef01");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("abcdef01");
    }

    @Test
    void doFilter_whenHeaderIsNotHex_generatesFreshTraceIdInsteadOfPropagating() throws Exception {
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "forged\r\nvalue");

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).matches("[0-9a-f]{8}");
    }

    @Test
    void currentTraceId_whenCalledOutsideRequest_bindsGeneratedIdToMdc() {
        String traceId = TraceIdFilter.currentTraceId();

        assertThat(traceId).matches("[0-9a-f]{8}");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo(traceId);
    }
}
