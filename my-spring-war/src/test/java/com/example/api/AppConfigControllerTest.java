package com.example.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigControllerTest {

    @Test
    void returnsPreferredTargetAndDisplayLanguageFromConfig() {
        AppConfigController controller = new AppConfigController(
                "fr", "bg", "en", "bg",
                "crm/data", "crm/glossary", "crm/adaptive", "crm/translated",
                "ss/data", "ss/glossary", "ss/adaptive", "ss/translated"
        );

        Map<String, String> config = controller.getConfig();

        assertEquals("fr", config.get("preferredTargetLanguage"));
        assertEquals("bg", config.get("configuredTargetLanguage"));
        assertEquals("en", config.get("displayLanguageCode"));
        assertEquals("bg", config.get("referenceLanguageFile"));
    }

    @Test
    void normalizesAndSanitizesConfiguredValues() {
        AppConfigController controller = new AppConfigController(
                "alltrans", "bg.json", "en", "bg.json",
                "crm/data", "crm/glossary", "crm/adaptive", "crm/translated",
                "ss/data", "ss/glossary", "ss/adaptive", "ss/translated"
        );

        Map<String, String> config = controller.getConfig();

        assertEquals("", config.get("preferredTargetLanguage"));
        assertEquals("bg", config.get("configuredTargetLanguage"));
        assertEquals("en", config.get("displayLanguageCode"));
        assertEquals("bg", config.get("referenceLanguageFile"));
    }
}
