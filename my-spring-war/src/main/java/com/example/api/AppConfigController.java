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
    private final String crmDataDirectory;
    private final String crmGlossaryDirectory;
    private final String crmAdaptiveDatasetDirectory;
    private final String crmTranslatedDirectory;
    private final String selfServiceDataDirectory;
    private final String selfServiceGlossaryDirectory;
    private final String selfServiceAdaptiveDatasetDirectory;
    private final String selfServiceTranslatedDirectory;

    public AppConfigController(
            @Value("${myapp.ui.preferredTargetLanguage:}") String preferredTargetLanguage,
            @Value("${myapp.google.targetLanguage:}") String configuredTargetLanguage,
            @Value("${myapp.google.supportedLanguagesDisplayLocale:en}") String displayLanguageCode,
            @Value("${myapp.referenceLanguageFile:en}") String referenceLanguageFile,
            @Value("${myapp.crm.sourceFilesDirectory:data}") String crmDataDirectory,
            @Value("${myapp.crm.glossaryDirectory:data}") String crmGlossaryDirectory,
            @Value("${myapp.crm.adaptiveDatasetDirectory:data}") String crmAdaptiveDatasetDirectory,
            @Value("${myapp.crm.translatedJsonDirectory:data}") String crmTranslatedDirectory,
            @Value("${myapp.selfService.sourceFilesDirectory:data}") String selfServiceDataDirectory,
            @Value("${myapp.selfService.glossaryDirectory:data}") String selfServiceGlossaryDirectory,
            @Value("${myapp.selfService.adaptiveDatasetDirectory:data}") String selfServiceAdaptiveDatasetDirectory,
            @Value("${myapp.selfService.translatedJsonDirectory:data}") String selfServiceTranslatedDirectory
    ) {
        this.preferredTargetLanguage = sanitizeLanguageCode(preferredTargetLanguage);
        this.configuredTargetLanguage = sanitizeLanguageCode(configuredTargetLanguage);
        this.displayLanguageCode = sanitizeLanguageCode(displayLanguageCode);
        this.referenceLanguageFile = normalizeLanguageCode(referenceLanguageFile);
        this.crmDataDirectory = crmDataDirectory;
        this.crmGlossaryDirectory = crmGlossaryDirectory;
        this.crmAdaptiveDatasetDirectory = crmAdaptiveDatasetDirectory;
        this.crmTranslatedDirectory = crmTranslatedDirectory;
        this.selfServiceDataDirectory = selfServiceDataDirectory;
        this.selfServiceGlossaryDirectory = selfServiceGlossaryDirectory;
        this.selfServiceAdaptiveDatasetDirectory = selfServiceAdaptiveDatasetDirectory;
        this.selfServiceTranslatedDirectory = selfServiceTranslatedDirectory;
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
        return Map.ofEntries(
                Map.entry("preferredTargetLanguage", preferredTargetLanguage),
                Map.entry("configuredTargetLanguage", configuredTargetLanguage),
                Map.entry("displayLanguageCode", displayLanguageCode),
                Map.entry("referenceLanguageFile", referenceLanguageFile),
                Map.entry("crmDataDirectory", crmDataDirectory),
                Map.entry("crmGlossaryDirectory", crmGlossaryDirectory),
                Map.entry("crmAdaptiveDatasetDirectory", crmAdaptiveDatasetDirectory),
                Map.entry("crmTranslatedDirectory", crmTranslatedDirectory),
                Map.entry("selfServiceDataDirectory", selfServiceDataDirectory),
                Map.entry("selfServiceGlossaryDirectory", selfServiceGlossaryDirectory),
                Map.entry("selfServiceAdaptiveDatasetDirectory", selfServiceAdaptiveDatasetDirectory),
                Map.entry("selfServiceTranslatedDirectory", selfServiceTranslatedDirectory)
        );
    }
}
