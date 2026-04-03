package com.example.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RawLocalPropertiesEnvironmentPostProcessorTest {

    @Test
    void parsesSingleLineWithEscapedNewlinesBeforePropertyKeys() {
        String raw = "myapp.local.dataDir=C:\\Users\\john\\translations\\nmyapp.local.supportedLanguagesDisplayLocale=en\\nmyapp.local.uiPreferredTargetLanguage=fr";

        Map<String, Object> parsed = RawLocalPropertiesEnvironmentPostProcessor.parseRawProperties(raw);

        assertEquals("C:\\Users\\john\\translations", parsed.get("myapp.local.dataDir"));
        assertEquals("en", parsed.get("myapp.local.supportedLanguagesDisplayLocale"));
        assertEquals("fr", parsed.get("myapp.local.uiPreferredTargetLanguage"));
    }

    @Test
    void keepsWindowsPathsContainingBackslashNSequences() {
        String raw = "myapp.local.googleCredentialsPath=C:\\Users\\zefislava.georgieva\\OneDrive - cerillion technologies\\Documents\\Translation service\\Google Credentials\\newtranslationservice-d28ee5ef6463.json";

        Map<String, Object> parsed = RawLocalPropertiesEnvironmentPostProcessor.parseRawProperties(raw);

        assertEquals(
                "C:\\Users\\zefislava.georgieva\\OneDrive - cerillion technologies\\Documents\\Translation service\\Google Credentials\\newtranslationservice-d28ee5ef6463.json",
                parsed.get("myapp.local.googleCredentialsPath")
        );
    }

    @Test
    void parsesNormalMultiLineProperties() {
        String raw = "myapp.local.googleApiKey=abc123\n" +
                "# comment\n" +
                "myapp.local.googleProjectId:demo-project";

        Map<String, Object> parsed = RawLocalPropertiesEnvironmentPostProcessor.parseRawProperties(raw);

        assertEquals("abc123", parsed.get("myapp.local.googleApiKey"));
        assertEquals("demo-project", parsed.get("myapp.local.googleProjectId"));
    }
}
