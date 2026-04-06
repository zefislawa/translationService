package com.example.api.dto;

public class GlossarySyncResponse {
    private final String sourceLanguage;
    private final String targetLanguage;
    private final String glossary;

    public GlossarySyncResponse(String sourceLanguage, String targetLanguage, String glossary) {
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.glossary = glossary;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public String getGlossary() {
        return glossary;
    }
}
