package com.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void prettifyAndTruncateBodyMasksBearerTokensAndOpenAiApiKeys() throws Exception {
        OutboundApiLoggingInterceptor interceptor = new OutboundApiLoggingInterceptor(new ObjectMapper());
        Method prettifyAndTruncateBody = OutboundApiLoggingInterceptor.class.getDeclaredMethod("prettifyAndTruncateBody", byte[].class);
        prettifyAndTruncateBody.setAccessible(true);

        String body = """
                {
                  "OPENAI_API_KEY": "sk-secret",
                  "message": "Authorization: Bearer ya29.google-token"
                }
                """;

        String sanitized = (String) prettifyAndTruncateBody.invoke(interceptor, body.getBytes(StandardCharsets.UTF_8));

        assertFalse(sanitized.contains("sk-secret"));
        assertFalse(sanitized.contains("ya29.google-token"));
    }
}
