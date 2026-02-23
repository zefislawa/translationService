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
    private final String displayLanguageCode;

    public AppConfigController(
            @Value("${myapp.ui.preferredTargetLanguage:}") String preferredTargetLanguage,
            @Value("${myapp.google.displayLanguageCode:}") String displayLanguageCode
    ) {
        this.preferredTargetLanguage = preferredTargetLanguage;
        this.displayLanguageCode = displayLanguageCode;
    }

    @GetMapping
    public Map<String, String> getConfig() {
        return Map.of(
                "preferredTargetLanguage", preferredTargetLanguage,
                "displayLanguageCode", displayLanguageCode
        );
    }
}
