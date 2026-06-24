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
    void rejectsTraceIdWithNewlines() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "fake-id\n2026-06-25 ERROR injected log line");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(request, response, (req, res) -> captured[0] = MDC.get(MdcKeys.TRACE_ID));

        assertNotEquals("fake-id\n2026-06-25 ERROR injected log line", captured[0]);
        assertTrue(captured[0].contains("-")); // UUID format
    }

    @Test
    void rejectsTraceIdExceeding64Chars() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "a".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(request, response, (req, res) -> captured[0] = MDC.get(MdcKeys.TRACE_ID));

        assertEquals(36, captured[0].length()); // UUID length
    }

    @Test
    void acceptsValidTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "abc-123-DEF-456");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(request, response, (req, res) -> captured[0] = MDC.get(MdcKeys.TRACE_ID));

        assertEquals("abc-123-DEF-456", captured[0]);
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
    }
}
