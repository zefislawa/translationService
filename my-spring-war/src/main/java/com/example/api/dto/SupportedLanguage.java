package com.example.api.dto;

public record SupportedLanguage(
        String languageCode,
        String displayName,
        boolean supportSource,
        boolean supportTarget
) {
}
