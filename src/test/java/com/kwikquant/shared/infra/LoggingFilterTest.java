package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoggingFilterTest {

    private final LoggingFilter filter = new LoggingFilter();

    @Test
    void setsTraceIdFromHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "custom-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(request, response, (req, res) -> captured[0] = MDC.get(MdcKeys.TRACE_ID));

        assertEquals("custom-trace-123", captured[0]);
        assertNull(MDC.get(MdcKeys.TRACE_ID));
    }

    @Test
    void generatesTraceIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(request, response, (req, res) -> captured[0] = MDC.get(MdcKeys.TRACE_ID));

        assertNotNull(captured[0]);
        assertFalse(captured[0].isBlank());
        assertNull(MDC.get(MdcKeys.TRACE_ID));
    }

    @Test
    void cleansUpMdcOnException() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(
                RuntimeException.class,
                () -> filter.doFilter(request, response, (req, res) -> {
                    throw new RuntimeException("boom");
                }));

        assertNull(MDC.get(MdcKeys.TRACE_ID));
        assertNull(MDC.get(MdcKeys.OWNER_USER_ID));
    }
}
