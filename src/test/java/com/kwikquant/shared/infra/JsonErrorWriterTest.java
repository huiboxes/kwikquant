package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class JsonErrorWriterTest {

    private final JsonErrorWriter writer = new JsonErrorWriter();

    @Test
    void writesJsonResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        JsonErrorWriter.write(response, 401, ErrorCode.UNAUTHENTICATED, "token expired");

        assertEquals(401, response.getStatus());
        assertEquals("application/json", response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("1001"));
        assertTrue(body.contains("token expired"));
    }

    @Test
    void writes403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        JsonErrorWriter.write(response, 403, ErrorCode.FORBIDDEN, "access denied");

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("1002"));
    }

    @Test
    void authenticationEntryPointReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.commence(request, response, new BadCredentialsException("bad"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("1001"));
        assertTrue(response.getContentAsString().contains("unauthenticated"));
    }

    @Test
    void accessDeniedHandlerReturns403() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.handle(request, response, new AccessDeniedException("denied"));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("1002"));
        assertTrue(response.getContentAsString().contains("access denied"));
    }
}
