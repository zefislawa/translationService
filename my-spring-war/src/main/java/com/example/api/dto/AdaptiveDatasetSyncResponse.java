package com.example.api.dto;

public class AdaptiveDatasetSyncResponse {
    private final String sourceLanguage;
    private final String targetLanguage;
    private final String dataset;
    private final String importStatus;
    private final String gcsUri;

    public AdaptiveDatasetSyncResponse(String sourceLanguage, String targetLanguage, String dataset, String importStatus, String gcsUri) {
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.dataset = dataset;
        this.importStatus = importStatus;
        this.gcsUri = gcsUri;
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

    public String getImportStatus() {
        return importStatus;
    }

    public String getGcsUri() {
        return gcsUri;
    }
}
