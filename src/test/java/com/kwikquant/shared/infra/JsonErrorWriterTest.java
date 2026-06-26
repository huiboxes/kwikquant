package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class JsonErrorWriterTest {

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
}
