package com.example.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigControllerTest {

    @Test
    void returnsPreferredTargetAndDisplayLanguageFromConfig() {
        AppConfigController controller = new AppConfigController(
                "fr", "bg", "en", "bg",
                "crm/data", "crm/glossary", "crm/adaptive", "crm/translated",
                "ss/data", "ss/glossary", "ss/adaptive", "ss/translated",
                "general/translation-llm", "risky-short",
                true, false
        );

        var config = controller.getConfig();

        assertEquals("fr", config.get("preferredTargetLanguage"));
        assertEquals("bg", config.get("configuredTargetLanguage"));
        assertEquals("en", config.get("displayLanguageCode"));
        assertEquals("bg", config.get("referenceLanguageFile"));
        assertEquals("general/translation-llm", config.get("googleModel"));
        assertEquals("risky-short", config.get("googleAdaptiveDatasetRoutingStrategy"));
        assertTrue((Boolean) config.get("googleGlossaryEnabled"));
        assertFalse((Boolean) config.get("googleAdaptiveDatasetEnabled"));
    }

    @Test
    void normalizesAndSanitizesConfiguredValues() {
        AppConfigController controller = new AppConfigController(
                "alltrans", "bg.json", "en", "bg.json",
                "crm/data", "crm/glossary", "crm/adaptive", "crm/translated",
                "ss/data", "ss/glossary", "ss/adaptive", "ss/translated",
                "general/translation-llm", "all",
                false, true
        );

        var config = controller.getConfig();

        assertEquals("", config.get("preferredTargetLanguage"));
        assertEquals("bg", config.get("configuredTargetLanguage"));
        assertEquals("en", config.get("displayLanguageCode"));
        assertEquals("bg", config.get("referenceLanguageFile"));
    }
}
