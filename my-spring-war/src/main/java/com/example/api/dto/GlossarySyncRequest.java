package com.example.api.dto;

public class GlossarySyncRequest {
    private String context;
    private String glossaryFilePath;
    private String sourceLanguage;
    private String targetLanguage;


    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }
    public String getGlossaryFilePath() {
        return glossaryFilePath;
    }

    public void setGlossaryFilePath(String glossaryFilePath) {
        this.glossaryFilePath = glossaryFilePath;
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
