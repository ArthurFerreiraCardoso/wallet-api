package com.example.wallet_api.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void generatesATraceIdAndCleansTheMdcAfterwards() throws Exception {
        AtomicReference<String> seenInChain = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> seenInChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(seenInChain.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(seenInChain.get());
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void keepsASafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "abc-123-def-456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("abc-123-def-456");
    }

    @ParameterizedTest
    @ValueSource(strings = { "short", "has space in it", "<script>alert(1)</script>", "line\nbreak-injected" })
    void replacesAnUnsafeIncomingTraceId(String unsafe) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, unsafe);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isNotEqualTo(unsafe).matches("[0-9a-f-]{36}");
    }
}
