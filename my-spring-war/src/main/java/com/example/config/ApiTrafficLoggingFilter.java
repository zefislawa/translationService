package com.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiTrafficLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTrafficLoggingFilter.class);
    private static final int MAX_LOG_BODY_LENGTH = 4_000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            String requestBody = extractBody(
                    wrappedRequest.getContentAsByteArray(),
                    wrappedRequest.getCharacterEncoding(),
                    wrappedRequest.getContentType()
            );
            String responseBody = extractBody(
                    wrappedResponse.getContentAsByteArray(),
                    wrappedResponse.getCharacterEncoding(),
                    wrappedResponse.getContentType()
            );

            log.info("Inbound API request -> method={}, path={}, query={}, headers={}, body={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getQueryString(),
                    extractRequestHeaders(request),
                    requestBody);

            log.info("Inbound API response <- method={}, path={}, status={}, headers={}, body={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    wrappedResponse.getStatus(),
                    extractResponseHeaders(wrappedResponse),
                    responseBody);

            wrappedResponse.copyBodyToResponse();
        }
    }

    private Map<String, List<String>> extractRequestHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name, Collections.list(request.getHeaders(name))));
        return headers;
    }

    private Map<String, List<String>> extractResponseHeaders(ContentCachingResponseWrapper response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.getHeaderNames().forEach(name -> headers.put(name, response.getHeaders(name)));
        return headers;
    }

    private String extractBody(byte[] body, String encoding, String contentType) {
        if (body == null || body.length == 0) {
            return "<empty>";
        }
        if (!isLoggableBodyContentType(contentType)) {
            return "<non-textual content>";
        }

        Charset charset = StringUtils.hasText(encoding)
                ? Charset.forName(encoding)
                : StandardCharsets.UTF_8;

        String text = new String(body, charset);
        if (text.length() <= MAX_LOG_BODY_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_LOG_BODY_LENGTH) + " ...<truncated>";
    }

    private boolean isLoggableBodyContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return true;
        }

        return contentType.contains(MediaType.APPLICATION_JSON_VALUE)
                || contentType.startsWith(MediaType.TEXT_PLAIN_VALUE)
                || contentType.startsWith(MediaType.APPLICATION_XML_VALUE)
                || contentType.contains("application/x-www-form-urlencoded")
                || contentType.contains("+json")
                || contentType.contains("+xml");
    }
}
