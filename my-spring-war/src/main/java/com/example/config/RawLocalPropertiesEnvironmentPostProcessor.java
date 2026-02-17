package com.example.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class RawLocalPropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "rawLocalProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path localPropertiesPath = Path.of("local.properties").toAbsolutePath().normalize();
        if (!Files.isRegularFile(localPropertiesPath)) {
            return;
        }

        try {
            String rawContent = Files.readString(localPropertiesPath, StandardCharsets.UTF_8);
            Map<String, Object> parsed = parseRawProperties(rawContent);
            if (!parsed.isEmpty()) {
                environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, parsed));
            }
        } catch (IOException ignored) {
            // Fallback to default Spring property loading.
        }
    }

    static Map<String, Object> parseRawProperties(String rawContent) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rawContent == null || rawContent.isBlank()) {
            return result;
        }

        String normalizedLines = rawContent.replace("\\r\\n", "\n").replace("\\n", "\n");
        for (String line : normalizedLines.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }

            int separatorIndex = indexOfSeparator(trimmed);
            if (separatorIndex <= 0) {
                continue;
            }

            String key = trimmed.substring(0, separatorIndex).trim();
            String value = trimmed.substring(separatorIndex + 1).trim();
            if (!key.isEmpty()) {
                result.put(key, value);
            }
        }

        return result;
    }

    private static int indexOfSeparator(String line) {
        int equalsIdx = line.indexOf('=');
        int colonIdx = line.indexOf(':');

        if (equalsIdx < 0) return colonIdx;
        if (colonIdx < 0) return equalsIdx;
        return Math.min(equalsIdx, colonIdx);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
