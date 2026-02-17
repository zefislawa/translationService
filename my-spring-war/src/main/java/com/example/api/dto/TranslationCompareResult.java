package com.example.api.dto;

import java.util.List;

public record TranslationCompareResult(
        String file1,
        String file2,
        List<TranslationCompareDifference> differences
) {
}
