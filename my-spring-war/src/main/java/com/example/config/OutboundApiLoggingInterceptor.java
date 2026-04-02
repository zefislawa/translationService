package com.example.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
                request.getHeaders(),
                prettifyAndTruncateBody(body));

        ClientHttpResponse response = execution.execute(request, body);
        try {
            byte[] responseBodyBytes = response.getBody().readAllBytes();

            log.info("Outbound API response <- method={}, uri={}, status={}, headers={}, body={}",
                    request.getMethod(),
                    request.getURI(),
                    response.getStatusCode(),
                    response.getHeaders(),
                    prettifyAndTruncateBody(responseBodyBytes));

            return new CachedBodyClientHttpResponse(response, responseBodyBytes);
        } catch (IOException ex) {
            log.warn("Outbound API response logging skipped -> method={}, uri={}, reason={}",
                    request.getMethod(),
                    request.getURI(),
                    ex.getMessage());
            return response;
        }
    }

    private String prettifyAndTruncateBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "<empty>";
        }

        String raw = new String(body, StandardCharsets.UTF_8);
        String formatted = raw;
        try {
            Object json = objectMapper.readTree(raw);
            formatted = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (JsonProcessingException ignored) {
            // Keep the raw body when it is not valid JSON.
        }

        if (formatted.length() <= MAX_LOG_BODY_LENGTH) {
            return formatted;
        }
        return formatted.substring(0, MAX_LOG_BODY_LENGTH) + " ...<truncated>";
    }
}
