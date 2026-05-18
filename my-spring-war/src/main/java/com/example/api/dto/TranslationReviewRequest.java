package com.example.api.dto;

import java.util.List;

public class TranslationReviewRequest {
    private String sourceLanguage;
    private String targetLanguage;
    private String context;
    private List<TranslationReviewItem> items;

    public String getSourceLanguage() { return sourceLanguage; }
    public void setSourceLanguage(String sourceLanguage) { this.sourceLanguage = sourceLanguage; }
    public String getTargetLanguage() { return targetLanguage; }
    public void setTargetLanguage(String targetLanguage) { this.targetLanguage = targetLanguage; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public List<TranslationReviewItem> getItems() { return items; }
    public void setItems(List<TranslationReviewItem> items) { this.items = items; }
}
