package com.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboundApiLoggingInterceptorTest {

    @Test
    @SuppressWarnings("unchecked")
    void sanitizeHeadersMasksAuthorizationValues() throws Exception {
        OutboundApiLoggingInterceptor interceptor = new OutboundApiLoggingInterceptor(new ObjectMapper());
        Method sanitizeHeaders = OutboundApiLoggingInterceptor.class.getDeclaredMethod("sanitizeHeaders", HttpHeaders.class);
        sanitizeHeaders.setAccessible(true);

        HttpHeaders headers = new HttpHeaders();
        headers.put(HttpHeaders.AUTHORIZATION, List.of("Bearer abc123", "Basic xyz"));

        HttpHeaders sanitized = (HttpHeaders) sanitizeHeaders.invoke(interceptor, headers);
        assertEquals(List.of("Bearer ***REDACTED***", "***REDACTED***"), sanitized.get(HttpHeaders.AUTHORIZATION));
    }
}
