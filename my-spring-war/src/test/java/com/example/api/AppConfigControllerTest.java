package com.example.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigControllerTest {

    @Test
    void returnsPreferredTargetAndDisplayLanguageFromConfig() {
        AppConfigController controller = new AppConfigController("fr", "en", "bg");

        Map<String, String> config = controller.getConfig();

        assertEquals("fr", config.get("preferredTargetLanguage"));
        assertEquals("en", config.get("displayLanguageCode"));
        assertEquals("bg", config.get("referenceLanguageFile"));
    }

    @Test
    void fallsBackDisplayLanguageToReferenceLanguageWhenDisplayLanguageMissing() {
        AppConfigController controller = new AppConfigController("fr", "", "bg.json");

        Map<String, String> config = controller.getConfig();

        assertEquals("bg", config.get("displayLanguageCode"));
        assertEquals("bg", config.get("referenceLanguageFile"));
    }
}
