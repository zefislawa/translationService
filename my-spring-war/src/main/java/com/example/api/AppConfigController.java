package com.example.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class AppConfigController {

    private final String preferredTargetLanguage;
    private final String configuredTargetLanguage;
    private final String displayLanguageCode;
    private final String referenceLanguageFile;

    public AppConfigController(
            @Value("${myapp.ui.preferredTargetLanguage:}") String preferredTargetLanguage,
            @Value("${myapp.google.targetLanguage:}") String configuredTargetLanguage,
            @Value("${myapp.google.supportedLanguagesDisplayLocale:en}") String displayLanguageCode,
            @Value("${myapp.referenceLanguageFile:en}") String referenceLanguageFile
    ) {
        this.preferredTargetLanguage = sanitizeLanguageCode(preferredTargetLanguage);
        this.configuredTargetLanguage = sanitizeLanguageCode(configuredTargetLanguage);
        this.displayLanguageCode = sanitizeLanguageCode(displayLanguageCode);
        this.referenceLanguageFile = normalizeLanguageCode(referenceLanguageFile);
    }

    private String normalizeLanguageCode(String rawLanguageCode) {
        if (rawLanguageCode == null || rawLanguageCode.isBlank()) {
            return "en";
        }

        String normalized = rawLanguageCode.trim();
        return normalized.toLowerCase().endsWith(".json")
                ? normalized.substring(0, normalized.length() - ".json".length())
                : normalized;
    }

    private String sanitizeLanguageCode(String rawLanguageCode) {
        String normalized = normalizeLanguageCode(rawLanguageCode);
        if ("alltrans".equalsIgnoreCase(normalized)) {
            return "";
        }
        return normalized;
    }

    @GetMapping
    public Map<String, String> getConfig() {
        return Map.of(
                "preferredTargetLanguage", preferredTargetLanguage,
                "configuredTargetLanguage", configuredTargetLanguage,
                "displayLanguageCode", displayLanguageCode,
                "referenceLanguageFile", referenceLanguageFile
        );
    }
}
