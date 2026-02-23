package com.example.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigControllerTest {

    @Test
    void returnsPreferredTargetAndDisplayLanguageFromConfig() {
        AppConfigController controller = new AppConfigController("fr", "en");

        Map<String, String> config = controller.getConfig();

        assertEquals("fr", config.get("preferredTargetLanguage"));
        assertEquals("en", config.get("displayLanguageCode"));
    }
}
