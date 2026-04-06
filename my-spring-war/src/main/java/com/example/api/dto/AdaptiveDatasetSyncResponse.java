package com.example.api.dto;

public class AdaptiveDatasetSyncResponse {
    private final String sourceLanguage;
    private final String targetLanguage;
    private final String dataset;

    public AdaptiveDatasetSyncResponse(String sourceLanguage, String targetLanguage, String dataset) {
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.dataset = dataset;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public String getDataset() {
        return dataset;
    }
}
