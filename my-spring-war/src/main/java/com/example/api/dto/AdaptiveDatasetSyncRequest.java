package com.example.api.dto;

public class AdaptiveDatasetSyncRequest {
    private String context;
    private String tsvFilePath;
    private String sourceLanguage;
    private String targetLanguage;


    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }
    public String getTsvFilePath() {
        return tsvFilePath;
    }

    public void setTsvFilePath(String tsvFilePath) {
        this.tsvFilePath = tsvFilePath;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public void setSourceLanguage(String sourceLanguage) {
        this.sourceLanguage = sourceLanguage;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public void setTargetLanguage(String targetLanguage) {
        this.targetLanguage = targetLanguage;
    }
}
