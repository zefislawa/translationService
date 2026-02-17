package com.example.api.dto;

public record TranslationCompareDifference(
        String keyPath,
        String valueInFile1,
        String valueInFile2,
        String status
) {
}
