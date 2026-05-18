package com.example.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class OutboundApiLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OutboundApiLoggingInterceptor.class);
    private static final int MAX_LOG_BODY_LENGTH = 4_000;

    private final ObjectMapper objectMapper;

    public OutboundApiLoggingInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        log.info("Outbound API request -> method={}, uri={}, headers={}, body={}",
                request.getMethod(),
                request.getURI(),
                sanitizeHeaders(request.getHeaders()),
                prettifyAndTruncateBody(body, request.getHeaders().getContentType()));

        ClientHttpResponse response = execution.execute(request, body);
        try {
            byte[] responseBodyBytes = response.getBody().readAllBytes();

            log.info("Outbound API response <- method={}, uri={}, status={}, headers={}, body={}",
                    request.getMethod(),
                    request.getURI(),
                    response.getStatusCode(),
                    sanitizeHeaders(response.getHeaders()),
                    prettifyAndTruncateBody(responseBodyBytes, response.getHeaders().getContentType()));

            return new CachedBodyClientHttpResponse(response, responseBodyBytes);
        } catch (IOException ex) {
            log.warn("Outbound API response logging skipped -> method={}, uri={}, reason={}",
                    request.getMethod(),
                    request.getURI(),
                    ex.getMessage());
            return response;
        }
    }

    private String prettifyAndTruncateBody(byte[] body, MediaType contentType) {
        if (body == null || body.length == 0) {
            return "<empty>";
        }

        Charset charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset()
                : StandardCharsets.UTF_8;
        String raw = new String(body, charset);
        String formatted = raw;
        try {
            Object json = objectMapper.readTree(raw);
            formatted = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (JsonProcessingException ignored) {
            // Keep the raw body when it is not valid JSON.
        }

        formatted = formatted
                .replaceAll("(?i)(OPENAI_API_KEY[\"']?\\s*[=:]\\s*[\"']?)[^\\s\",}]+", "$1***REDACTED***")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***REDACTED***");

        if (formatted.length() <= MAX_LOG_BODY_LENGTH) {
            return formatted;
        }
        return formatted.substring(0, MAX_LOG_BODY_LENGTH) + " ...<truncated>";
    }

    private HttpHeaders sanitizeHeaders(HttpHeaders headers) {
        HttpHeaders sanitized = new HttpHeaders();
        sanitized.putAll(headers);
        if (sanitized.containsKey(HttpHeaders.AUTHORIZATION)) {
            List<String> original = sanitized.get(HttpHeaders.AUTHORIZATION);
            List<String> masked = new ArrayList<>();
            if (original != null) {
                for (String value : original) {
                    if (value == null || value.isBlank()) {
                        masked.add("***REDACTED***");
                    } else if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                        masked.add("Bearer ***REDACTED***");
                    } else {
                        masked.add("***REDACTED***");
                    }
                }
            }
            sanitized.put(HttpHeaders.AUTHORIZATION, masked);
        }
        return sanitized;
    }
}
