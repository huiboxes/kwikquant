package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class UserContextFilterTest {

    private final UserContextFilter filter = new UserContextFilter();

    @Test
    void setsOwnerUserIdWhenAuthenticated() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("user-42", "pass", List.of()));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            String[] captured = new String[1];

            filter.doFilter(request, response, (req, res) -> captured[0] = MDC.get(MdcKeys.OWNER_USER_ID));

            assertEquals("user-42", captured[0]);
            assertNull(MDC.get(MdcKeys.OWNER_USER_ID));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void ownerUserIdNullWhenNotAuthenticated() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] captured = new String[1];

        filter.doFilter(request, response, (req, res) -> captured[0] = MDC.get(MdcKeys.OWNER_USER_ID));

        assertNull(captured[0]);
    }

    @Test
    void cleansUpMdcOnException() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("user-42", "pass", List.of()));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThrows(
                    RuntimeException.class,
                    () -> filter.doFilter(request, response, (req, res) -> {
                        throw new RuntimeException("boom");
                    }));

            assertNull(MDC.get(MdcKeys.OWNER_USER_ID));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
