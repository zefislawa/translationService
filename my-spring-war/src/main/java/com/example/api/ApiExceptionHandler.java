package com.example.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ObjectMapper mapper;

    public ApiExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(CancellationException.class)
    public ResponseEntity<Map<String, Object>> handleCancellation(CancellationException ex) {
        return error(HttpStatus.CONFLICT, "Translation was cancelled. No more translation batches will be started.");
    }

    @ExceptionHandler(HttpStatusCodeException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamHttpError(HttpStatusCodeException ex) {
        String upstreamMessage = extractUpstreamMessage(ex.getResponseBodyAsString());
        HttpStatus status = ex.getStatusCode().is4xxClientError()
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.BAD_GATEWAY;
        return error(status, "Translation provider rejected the request: " + upstreamMessage);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> handleResourceAccess(ResourceAccessException ex) {
        return error(HttpStatus.BAD_GATEWAY, "Unable to reach the translation provider. Check the network connection and try again.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled API error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Translation failed unexpectedly. Check the logs for details and try again.");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message == null || message.isBlank() ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(body);
    }

    private String extractUpstreamMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "the provider returned an error without details.";
        }

        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode error = root.path("error");
            String message = error.path("message").asText("");
            JsonNode violations = error.path("details").isArray() && !error.path("details").isEmpty()
                    ? error.path("details").get(0).path("fieldViolations")
                    : null;
            if (violations != null && violations.isArray() && !violations.isEmpty()) {
                String description = violations.get(0).path("description").asText("");
                if (!description.isBlank()) {
                    return message.isBlank() ? description : message + " " + description;
                }
            }
            if (!message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
            // Fall through to the trimmed response body.
        }

        return responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody;
    }
}
